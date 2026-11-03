package com.jobradar.company;

import com.jobradar.config.RateLimiter;
import com.jobradar.dedupe.Normalizer;
import com.jobradar.source.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Locale;

/**
 * Decides whether a guessed ATS tenant really belongs to the company.
 *
 * <p>Two independent kinds of evidence are accepted:
 * <ol>
 *   <li>the board itself reports a company name that matches (Greenhouse, Recruitee);</li>
 *   <li>the company's own website links to that board.</li>
 * </ol>
 * Anything else stays GUESSED and waits for one click of human confirmation, because a
 * wrong tenant does not fail loudly - it quietly fills the list with another company's jobs.
 */
@Component
public class DetectionVerifier {

    private static final Logger log = LoggerFactory.getLogger(DetectionVerifier.class);

    private final RestClient http;
    private final RateLimiter rateLimiter;
    private final Normalizer normalizer;

    public DetectionVerifier(RestClient http, RateLimiter rateLimiter, Normalizer normalizer) {
        this.http = http;
        this.rateLimiter = rateLimiter;
        this.normalizer = normalizer;
    }

    public record Verdict(String confidence, String evidence) {
        static Verdict confirmed(String why) { return new Verdict("CONFIRMED", why); }
        static Verdict guessed(String why)   { return new Verdict("GUESSED", why); }
        static Verdict rejected(String why)  { return new Verdict("REJECTED", why); }
    }

    public Verdict verify(Company c) {
        if (c.atsTenant() == null) return Verdict.rejected("no tenant");

        if (looksLikeSampleBoard(c.atsType(), c.atsTenant())) {
            return Verdict.rejected("sample/demo board");
        }

        String boardName = boardName(c.atsType(), c.atsTenant());
        if (boardName != null) {
            if (looksLikeDemo(boardName)) return Verdict.rejected("demo board: " + boardName);
            if (namesMatch(c.name(), boardName)) {
                return Verdict.confirmed("board name \"" + boardName + "\" matches");
            }
            return Verdict.rejected("board is named \"" + boardName + "\"");
        }

        // No name from the API: ask the company's own site whether it links to this board.
        // The site must be the company's own domain - asking the ATS about itself proves nothing.
        String site = c.careersUrl();
        if (site != null && !site.isBlank() && !isAtsHost(site)) {
            String host = atsHostFragment(c.atsType(), c.atsTenant());
            for (String url : List.of(withScheme(site), withScheme(site) + "/careers", withScheme(site) + "/jobs")) {
                String html = get(url);
                if (html != null && host != null && html.toLowerCase(Locale.ROOT).contains(host)) {
                    return Verdict.confirmed("linked from " + url);
                }
            }
        }
        return Verdict.guessed("slug guess, unverified");
    }

    private static final List<String> ATS_HOSTS = List.of(
            "greenhouse.io", "lever.co", "ashbyhq.com", "jobs.personio.", "recruitee.com",
            "myworkdayjobs.com", "smartrecruiters.com");

    private static boolean isAtsHost(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        return ATS_HOSTS.stream().anyMatch(u::contains);
    }

    /** Demo and sandbox boards exist on every ATS and answer to obvious slugs. */
    private boolean looksLikeSampleBoard(SourceType type, String tenant) {
        String body = switch (type) {
            case RECRUITEE -> get("https://" + tenant + ".recruitee.com/api/offers/");
            case PERSONIO  -> get("https://" + tenant + ".jobs.personio.de/xml?language=en");
            default -> null;
        };
        if (body == null) return false;
        String b = body.toLowerCase(Locale.ROOT);
        return b.contains("lorem ipsum") || b.contains("(sample)") || b.contains("sample)\"");
    }

    /** The board's own idea of who it belongs to, where the API exposes one. */
    private String boardName(SourceType type, String tenant) {
        return switch (type) {
            case GREENHOUSE -> jsonField("https://boards-api.greenhouse.io/v1/boards/" + tenant, "name");
            case RECRUITEE -> {
                String body = get("https://" + tenant + ".recruitee.com/api/offers/");
                yield body == null ? null : extract(body, "\"company_name\":\"");
            }
            // Every posting carries {"company": {"identifier": ..., "name": ...}}.
            case SMARTRECRUITERS -> {
                String body = get("https://api.smartrecruiters.com/v1/companies/" + tenant + "/postings?limit=1");
                if (body == null) yield null;
                try {
                    String name = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body)
                            .path("content").path(0).path("company").path("name").asText("");
                    yield name.isBlank() ? null : name;
                } catch (Exception e) {
                    yield null;
                }
            }
            default -> null;
        };
    }

    private String jsonField(String url, String field) {
        String body = get(url);
        return body == null ? null : extract(body, "\"" + field + "\":\"");
    }

    private static String extract(String body, String marker) {
        int i = body.indexOf(marker);
        if (i < 0) return null;
        int start = i + marker.length();
        int end = body.indexOf('"', start);
        return end < 0 ? null : body.substring(start, end);
    }

    private String atsHostFragment(SourceType type, String tenant) {
        return switch (type) {
            case GREENHOUSE -> "greenhouse.io/" + tenant;
            case LEVER      -> "lever.co/" + tenant;
            case ASHBY      -> "ashbyhq.com/" + tenant;
            case PERSONIO   -> tenant + ".jobs.personio";
            case RECRUITEE  -> tenant + ".recruitee.com";
            case WORKDAY    -> tenant + ".wd";
            case SMARTRECRUITERS -> "smartrecruiters.com/" + tenant;
            case SUCCESSFACTORS -> tenant;
            default -> null;
        };
    }

    private boolean namesMatch(String companyName, String boardName) {
        String a = normalizer.normalizeCompany(companyName);
        String b = normalizer.normalizeCompany(boardName);
        if (a.isBlank() || b.isBlank()) return false;
        if (a.equals(b) || a.startsWith(b) || b.startsWith(a)) return true;
        // Entries like "Wolt (DoorDash)" or "IONOS / 1&1" hold several names; any token run matching is enough.
        String firstA = a.split(" ")[0], firstB = b.split(" ")[0];
        return firstA.length() > 3 && firstA.equals(firstB);
    }

    private static boolean looksLikeDemo(String boardName) {
        String n = boardName.toLowerCase(Locale.ROOT);
        return n.contains("sample") || n.contains("demo") || n.contains("test account");
    }

    private String get(String url) {
        rateLimiter.acquire(url);
        try {
            var res = http.get().uri(url).retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                    .toEntity(String.class);
            return res.getStatusCode().is2xxSuccessful() ? res.getBody() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String withScheme(String s) {
        String t = s.trim();
        return t.startsWith("http") ? t : "https://" + t;
    }
}
