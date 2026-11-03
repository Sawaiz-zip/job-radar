package com.jobradar.ingest;

import com.jobradar.company.*;
import com.jobradar.dedupe.Normalizer;
import com.jobradar.dedupe.ParsedJobUrl;
import com.jobradar.dedupe.UrlCanonicalizer;
import com.jobradar.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Adds a company the CV tool just worked on. The job URL is the strongest evidence
 * available: if it points at an ATS board, that board provably belongs to this employer,
 * so the tenant is confirmed outright instead of going through the guess-and-review path.
 */
@Service
public class AddCompanyService {

    private static final Logger log = LoggerFactory.getLogger(AddCompanyService.class);

    private final CompanyRepository companies;
    private final Normalizer normalizer;
    private final UrlCanonicalizer canonicalizer;
    private final AtsProbe probe;
    private final DetectionVerifier verifier;
    private final ScanService scans;

    public AddCompanyService(CompanyRepository companies, Normalizer normalizer,
                             UrlCanonicalizer canonicalizer, AtsProbe probe,
                             DetectionVerifier verifier, ScanService scans) {
        this.companies = companies;
        this.normalizer = normalizer;
        this.canonicalizer = canonicalizer;
        this.probe = probe;
        this.verifier = verifier;
        this.scans = scans;
    }

    public Map<String, Object> add(String name, String website, String jobUrl, boolean scanNow) {
        Map<String, Object> result = new LinkedHashMap<>();
        String normalized = normalizer.normalizeCompany(name);
        if (normalized.isBlank()) {
            result.put("ok", false);
            result.put("message", "a company name is required");
            return result;
        }

        Optional<Company> existing = companies.findByNormalizedName(normalized);
        boolean created = existing.isEmpty();

        long id = companies.upsertSeed(name.trim(), normalized, null, null,
                website == null || website.isBlank() ? null : website.trim(),
                null, "B", "CV_TOOL", "added from the CV tool");

        Company company = companies.findById(id).orElseThrow();
        result.put("companyId", id);
        result.put("name", company.name());
        result.put("created", created);

        // 1. The job URL itself, when it is an ATS link, names the tenant with certainty.
        if (company.atsTenant() == null && jobUrl != null && !jobUrl.isBlank()) {
            ParsedJobUrl parsed = canonicalizer.parse(jobUrl);
            if (parsed.isKnownAts() && parsed.tenant() != null) {
                // One board belongs to one employer. If another company already owns this
                // tenant the URL does not match the name, which is worth saying out loud
                // rather than silently attaching the wrong board.
                Optional<Company> owner = companies.findByAts(parsed.type(), parsed.tenant());
                if (owner.isPresent() && owner.get().id() != id) {
                    result.put("ok", false);
                    result.put("message", "That job board (%s:%s) already belongs to %s. "
                            .formatted(parsed.type().name().toLowerCase(), parsed.tenant(),
                                       owner.get().name())
                            + "%s was added to the watchlist without a board."
                                    .formatted(company.name()));
                    return result;
                }
                companies.saveDetection(id, parsed.type(), parsed.tenant(), parsed.site(), website,
                        "CONFIRMED", "from the job URL you pasted into the CV tool");
                company = companies.findById(id).orElseThrow();
                result.put("detectedFrom", "job url");
            }
        }

        // 2. Otherwise fall back to guessing the slug, which still needs confirming.
        if (company.atsTenant() == null) {
            Optional<AtsDetector.Detection> found = probe.probe(company);
            if (found.isPresent() && companies.findByAts(found.get().type(), found.get().tenant())
                    .filter(owner -> owner.id() != id).isEmpty()) {
                AtsDetector.Detection d = found.get();
                companies.saveDetection(id, d.type(), d.tenant(), null, website, "GUESSED", "slug probe");
                company = companies.findById(id).orElseThrow();
                DetectionVerifier.Verdict verdict = verifier.verify(company);
                if ("REJECTED".equals(verdict.confidence())) {
                    companies.clearDetection(id, verdict.evidence());
                    company = companies.findById(id).orElseThrow();
                } else {
                    companies.saveConfidence(id, verdict.confidence(), verdict.evidence());
                    company = companies.findById(id).orElseThrow();
                    result.put("detectedFrom", "slug probe");
                }
            }
        }

        result.put("ats", company.atsType().name());
        result.put("tenant", company.atsTenant());

        Company finalCompany = companies.findById(id).orElseThrow();
        String confidence = confidenceOf(id);
        result.put("confidence", confidence);

        if (finalCompany.atsTenant() == null) {
            result.put("ok", true);
            result.put("message", "Added to the watchlist, but no job board was found. "
                    + "It will not be scanned until one is.");
            return result;
        }
        if (!"CONFIRMED".equals(confidence)) {
            result.put("ok", true);
            result.put("message", "Added. Found a possible board (%s:%s) - confirm it in Job Radar before it is scanned."
                    .formatted(finalCompany.atsType().name().toLowerCase(), finalCompany.atsTenant()));
            return result;
        }

        if (scanNow) {
            ScanService.CompanyScan scan = scans.scanCompany(finalCompany);
            result.put("jobsFound", scan.found());
            result.put("jobsNew", scan.created());
            result.put("scanError", scan.error());

            if (scan.error() != null && scan.error().contains("404")) {
                // The tenant does not exist. Keeping it as CONFIRMED would leave a board in
                // the registry that can never return anything, so drop it and say so.
                companies.clearDetection(id, "board returned 404 on the first scan");
                result.put("confidence", "REJECTED");
                result.put("tenant", null);
                result.put("ok", true);
                result.put("message", "Added to the watchlist, but that job board does not exist "
                        + "(404). No board is attached, so it will not be scanned.");
                return result;
            }
            result.put("message", scan.error() != null
                    ? "Added, but the first scan failed: " + scan.error()
                    : "Added and scanned: %d jobs (%d new). Future scans include it automatically."
                            .formatted(scan.found(), scan.created()));
        } else {
            result.put("message", "Added and confirmed. It will be included from the next scan.");
        }
        result.put("ok", true);
        return result;
    }

    private String confidenceOf(long id) {
        return companies.findByConfidence("CONFIRMED").stream().anyMatch(c -> c.id() == id)
                ? "CONFIRMED"
                : companies.findByConfidence("GUESSED").stream().anyMatch(c -> c.id() == id)
                    ? "GUESSED" : "NONE";
    }
}
