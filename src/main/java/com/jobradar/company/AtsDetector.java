package com.jobradar.company;

import com.jobradar.config.RateLimiter;
import com.jobradar.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds which ATS a company uses by reading its careers page and looking for the
 * board host in links, scripts and iframes. Public pages only, one request.
 */
@Component
public class AtsDetector {

    private static final Logger log = LoggerFactory.getLogger(AtsDetector.class);

    private record Rule(SourceType type, Pattern pattern) {}

    private static final List<Rule> RULES = List.of(
            new Rule(SourceType.GREENHOUSE, Pattern.compile("(?:job-)?boards(?:\\.eu)?\\.greenhouse\\.io/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.GREENHOUSE, Pattern.compile("boards-api\\.greenhouse\\.io/v1/boards/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.LEVER,      Pattern.compile("jobs(?:\\.eu)?\\.lever\\.co/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.ASHBY,      Pattern.compile("jobs\\.ashbyhq\\.com/([a-z0-9_.-]+)", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.RECRUITEE,  Pattern.compile("([a-z0-9-]+)\\.recruitee\\.com", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.PERSONIO,   Pattern.compile("([a-z0-9-]+)\\.jobs\\.personio\\.(?:de|com)", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.SMARTRECRUITERS, Pattern.compile("(?:jobs|careers)\\.smartrecruiters\\.com/([a-z0-9_-]+)", Pattern.CASE_INSENSITIVE)),
            new Rule(SourceType.WORKDAY,    Pattern.compile("([a-z0-9-]+)\\.wd\\d+\\.myworkdayjobs\\.com", Pattern.CASE_INSENSITIVE)));

    /**
     * Workday needs three parts, not one: the tenant, the data centre, and the site name,
     * which is arbitrary per customer ("NVIDIAExternalCareerSite", "SAPCareers") and can
     * only be read off a real careers URL.
     */
    private static final Pattern WORKDAY_FULL = Pattern.compile(
            "([a-z0-9-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com/(?:[a-z]{2}(?:-[A-Za-z]{2})?/)?([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);

    /** SuccessFactors career sites have no tenant slug; the host itself is the identifier. */
    private static final Pattern SUCCESSFACTORS = Pattern.compile(
            "https?://([a-z0-9.-]*(?:jobs|careers|career)\\.[a-z0-9.-]+)/(?:search|job)/",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SF_MARKER = Pattern.compile(
            "successfactors|jobTitle-link|data-careersite|/services/recruiting/",
            Pattern.CASE_INSENSITIVE);

    private final RestClient http;
    private final RateLimiter rateLimiter;

    public AtsDetector(RestClient http, RateLimiter rateLimiter) {
        this.http = http;
        this.rateLimiter = rateLimiter;
    }

    public record Detection(SourceType type, String tenant, String site, String region,
                            String checkedUrl, String error) {
        public Detection(SourceType type, String tenant, String checkedUrl, String error) {
            this(type, tenant, null, null, checkedUrl, error);
        }

        public boolean found() {
            return type != SourceType.UNKNOWN;
        }
    }

    public Detection detect(Company company) {
        String url = candidateUrl(company);
        if (url == null) return new Detection(SourceType.UNKNOWN, null, null, "no website or careers url");

        for (String candidate : List.of(url, url + "/careers", url + "/jobs", url + "/en/careers")) {
            Detection d = probe(candidate);
            if (d.found()) return d;
        }
        return new Detection(SourceType.UNKNOWN, null, url, null);
    }

    private Detection probe(String url) {
        rateLimiter.acquire(url);
        try {
            String html = http.get().uri(url).retrieve().body(String.class);
            if (html == null) return new Detection(SourceType.UNKNOWN, null, url, "empty body");
            // Workday first: it needs the site name, which the generic host rule cannot see.
            Matcher wd = WORKDAY_FULL.matcher(html);
            while (wd.find()) {
                String site = wd.group(3);
                if (isNoise(site) || site.equalsIgnoreCase("wday")) continue;
                return new Detection(SourceType.WORKDAY, wd.group(1).toLowerCase(),
                        site, wd.group(2).toLowerCase(), url, null);
            }

            // SuccessFactors is recognised by its markup, since the host tells you nothing.
            if (SF_MARKER.matcher(html).find()) {
                Matcher sf = SUCCESSFACTORS.matcher(html);
                if (sf.find()) {
                    return new Detection(SourceType.SUCCESSFACTORS, sf.group(1).toLowerCase(),
                            null, null, url, null);
                }
                String host = java.net.URI.create(url).getHost();
                if (host != null) {
                    return new Detection(SourceType.SUCCESSFACTORS, host.toLowerCase(),
                            null, null, url, null);
                }
            }

            for (Rule r : RULES) {
                Matcher m = r.pattern().matcher(html);
                if (m.find()) {
                    String tenant = m.group(1).toLowerCase();
                    if (isNoise(tenant)) continue;
                    return new Detection(r.type(), tenant, url, null);
                }
            }
            return new Detection(SourceType.UNKNOWN, null, url, null);
        } catch (RuntimeException e) {
            return new Detection(SourceType.UNKNOWN, null, url, e.getClass().getSimpleName());
        }
    }

    /** Generic path segments that look like a tenant but are not one. */
    private static boolean isNoise(String tenant) {
        return List.of("embed", "www", "job", "jobs", "board", "boards", "static", "assets", "api")
                   .contains(tenant);
    }

    private static String candidateUrl(Company c) {
        if (c.careersUrl() != null && !c.careersUrl().isBlank()) return withScheme(c.careersUrl());
        return null;
    }

    private static String withScheme(String s) {
        String t = s.trim();
        return t.startsWith("http") ? t : "https://" + t;
    }
}
