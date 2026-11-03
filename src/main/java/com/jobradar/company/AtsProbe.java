package com.jobradar.company;

import com.jobradar.config.RateLimiter;
import com.jobradar.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * Second detection strategy, and in practice the one that works.
 *
 * <p>Reading a careers page only finds the ATS when the link is in the server-rendered
 * HTML, which is rarely true any more. Most boards, though, are addressed by a tenant
 * slug that is simply the company name, so asking the ATS API directly whether a tenant
 * exists is both cheaper and far more reliable. A 200 with at least one posting is proof;
 * anything else is a no.
 */
@Component
public class AtsProbe {

    private static final Logger log = LoggerFactory.getLogger(AtsProbe.class);

    private record Endpoint(SourceType type, String urlTemplate, String mustContain) {}

    private static final List<Endpoint> ENDPOINTS = List.of(
            new Endpoint(SourceType.GREENHOUSE, "https://boards-api.greenhouse.io/v1/boards/%s/jobs", "\"jobs\""),
            new Endpoint(SourceType.LEVER,      "https://api.lever.co/v0/postings/%s?mode=json", "["),
            new Endpoint(SourceType.ASHBY,      "https://api.ashbyhq.com/posting-api/job-board/%s", "\"jobs\""),
            new Endpoint(SourceType.PERSONIO,   "https://%s.jobs.personio.de/xml?language=en", "<position>"),
            new Endpoint(SourceType.RECRUITEE,  "https://%s.recruitee.com/api/offers/", "\"offers\""),
            // Answers 200 with an empty list for ANY slug, so only a non-empty German list
            // counts - which also skips employers with no German postings at all.
            new Endpoint(SourceType.SMARTRECRUITERS,
                    "https://api.smartrecruiters.com/v1/companies/%s/postings?country=de&limit=1", "\"content\":[{"));

    private final RestClient http;
    private final RateLimiter rateLimiter;

    public AtsProbe(RestClient http, RateLimiter rateLimiter) {
        this.http = http;
        this.rateLimiter = rateLimiter;
    }

    public Optional<AtsDetector.Detection> probe(Company company) {
        for (String slug : candidateSlugs(company)) {
            for (Endpoint e : ENDPOINTS) {
                String url = e.urlTemplate().formatted(slug);
                if (hasPostings(url, e.mustContain())) {
                    log.info("{} -> {}:{}", company.name(), e.type(), slug);
                    return Optional.of(new AtsDetector.Detection(e.type(), slug, url, null));
                }
            }
        }
        return Optional.empty();
    }

    private boolean hasPostings(String url, String mustContain) {
        rateLimiter.acquire(url);
        try {
            var response = http.get().uri(url).retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> { })   // a 404 is an answer, not a failure
                    .toEntity(String.class);
            if (!response.getStatusCode().is2xxSuccessful()) return false;
            String body = response.getBody();
            if (body == null || !body.contains(mustContain)) return false;
            // An existing-but-empty board looks identical to a wrong slug on some ATSs,
            // so require real content before claiming a match.
            return !body.contains("\"jobs\":[]") && !body.equals("[]") && body.length() > 40;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Slug candidates, most likely first: the domain label, then the name, de-spaced. */
    static List<String> candidateSlugs(Company company) {
        Set<String> out = new LinkedHashSet<>();
        String domain = company.careersUrl();
        if (domain != null && !domain.isBlank()) {
            String label = domain.replaceFirst("^https?://", "").replaceFirst("^www\\.", "")
                                 .split("/")[0].split("\\.")[0];
            add(out, label);
        }
        String name = company.normalizedName();
        if (name != null && !name.isBlank()) {
            add(out, name.replace(" ", ""));
            add(out, name.replace(" ", "-"));
            // "deutsche telekom t systems" style entries: the first word is usually the brand.
            add(out, name.split(" ")[0]);
        }
        out.removeIf(s -> s.length() < 3);
        return new ArrayList<>(out);
    }

    private static void add(Set<String> out, String slug) {
        if (slug == null) return;
        String s = slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
        if (!s.isBlank()) out.add(s);
    }
}
