package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.config.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The Bundesagentur für Arbeit job search - by far the largest German job database, and the
 * only way to reach the Mittelstand employers that have no modern ATS.
 *
 * <p>The API is community-documented rather than official (the key below is the public one
 * the BA's own web client uses), so breakages are expected and are reported like any other
 * source failure. Temp agencies are excluded at the query level: they repost the same roles
 * endlessly and drown out the employers themselves.
 *
 * <p>The result list carries no description, and the description is what the language and
 * experience filters need. Details are therefore fetched only for postings whose title
 * already passed, which keeps this to a few dozen extra requests instead of hundreds.
 */
@Component
public class BundesagenturAdapter extends AbstractHttpAdapter implements BoardAdapter {

    private static final String SEARCH =
            "https://rest.arbeitsagentur.de/jobboerse/jobsuche-service/pc/v6/jobs"
          + "?was=%s&wo=Deutschland&size=%d&page=%d&zeitarbeit=false&angebotsart=1"
          + "&veroeffentlichtseit=%d";
    private static final String DETAIL =
            "https://rest.arbeitsagentur.de/jobboerse/jobsuche-service/pc/v4/jobdetails/%s";
    private static final String PUBLIC_KEY = "jobboerse-jobsuche";

    /** Results per page; the API accepts up to 100. */
    private static final int PAGE_SIZE = 100;

    /**
     * BA matches loosely, so every extra query multiplies the noise. A short, specific list
     * beats feeding it all 30-odd tag-bar terms: "Kubernetes" or "Agents" alone drag in
     * hundreds of unrelated Mittelstand ads.
     */
    private final List<String> queries;
    private final int maxDetailFetches;
    private final int publishedWithinDays;
    private final int maxPages;

    public BundesagenturAdapter(RestClient http, RateLimiter rateLimiter,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${jobradar.ba.queries:}") List<String> configuredQueries,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${jobradar.ba.max-detail-fetches:250}") int maxDetailFetches,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${jobradar.ba.published-within-days:28}") int publishedWithinDays,
                                @org.springframework.beans.factory.annotation.Value(
                                        "${jobradar.ba.max-pages:10}") int maxPages) {
        super(http, rateLimiter);
        this.queries = configuredQueries == null || configuredQueries.isEmpty()
                ? List.of("Java Entwickler", "Softwareentwickler Java", "Backend Entwickler",
                          "Software Engineer", "AI Engineer")
                : configuredQueries;
        this.maxDetailFetches = maxDetailFetches;
        this.publishedWithinDays = Math.max(1, Math.min(publishedWithinDays, 100));
        this.maxPages = Math.max(1, maxPages);
    }

    @Override
    public SourceType type() {
        return SourceType.BA;
    }

    @Override
    public boolean supports(com.jobradar.company.Company company) {
        return false;
    }

    @Override
    public FetchResult fetch(com.jobradar.company.Company company) {
        return FetchResult.failed("BA is a board source, not a company source", null);
    }

    @Override
    public FetchResult search(List<String> ignoredTagBarTerms) {
        List<RawJob> out = new ArrayList<>();
        Set<String> seenRefs = new LinkedHashSet<>();
        String lastError = null;

        // Every page of what was published recently, rather than the first 50 by relevance:
        // measured on 2026-11-10, the six default queries had 2,900 results and the old
        // single page read 300 of them, chosen by relevance, so most new postings never
        // arrived. A recent window keeps the volume small while missing nothing new.
        for (String query : queries) {
            for (int page = 1; page <= maxPages; page++) {
                String url = SEARCH.formatted(URLEncoder.encode(query, StandardCharsets.UTF_8),
                        PAGE_SIZE, page, publishedWithinDays);
                int[] total = {0};
                FetchResult result = callWithKey(url, root -> {
                    total[0] = root.path("maxErgebnisse").asInt(0);
                    List<RawJob> jobs = new ArrayList<>();
                    for (JsonNode j : root.path("ergebnisliste")) {
                        String ref = j.path("referenznummer").asText(null);
                        if (ref == null || !seenRefs.add(ref)) continue;
                        jobs.add(new RawJob(
                                type(), null, ref,
                                j.path("stellenangebotsTitel").asText(null),
                                location(j),
                                null,                       // filled in later, only if the title passes
                                jobUrl(j, ref),
                                j.path("firma").asText(null),
                                publishedAt(j),
                                null));
                    }
                    return FetchResult.ok(jobs, 200);
                });
                if (result.isError()) {
                    lastError = result.error();
                    break;
                }
                out.addAll(result.jobs());
                if (page * PAGE_SIZE >= total[0]) break;
            }
        }

        if (out.isEmpty() && lastError != null) return FetchResult.failed(lastError, null);
        return FetchResult.ok(out, 200);
    }

    /**
     * Fetches the full text for one posting. Called by the scan only for jobs whose title
     * survived the rules, because the description is a second request each.
     */
    public String fetchDescription(String referenznummer) {
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(referenznummer.getBytes(StandardCharsets.UTF_8));
        FetchResult result = callWithKey(DETAIL.formatted(encoded), root -> {
            String text = root.path("stellenangebotsBeschreibung").asText(null);
            return FetchResult.ok(text == null ? List.of()
                    : List.of(new RawJob(type(), null, referenznummer, "", null, text, null, null, null, null)), 200);
        });
        if (result.isError() || result.jobs().isEmpty()) return null;
        return result.jobs().get(0).descriptionText();
    }

    @Override
    public String fetchDescription(RawJob job) {
        return job.externalId() == null ? null : fetchDescription(job.externalId());
    }

    @Override
    public int maxDetailFetches() {
        return maxDetailFetches;
    }

    private FetchResult callWithKey(String url, java.util.function.Function<JsonNode, FetchResult> mapper) {
        rateLimiter.acquire(url);
        try {
            String raw = http.get().uri(url)
                    .header("X-API-Key", PUBLIC_KEY)
                    .retrieve().body(String.class);
            if (raw == null || raw.isBlank()) return FetchResult.failed("empty body", 200);
            return mapper.apply(new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw));
        } catch (org.springframework.web.client.RestClientResponseException e) {
            return FetchResult.failed(e.getStatusCode() + " from BA", e.getStatusCode().value());
        } catch (Exception e) {
            return FetchResult.failed(e.getClass().getSimpleName() + " from BA", null);
        }
    }

    private static String location(JsonNode j) {
        JsonNode first = j.path("stellenlokationen").path(0).path("adresse");
        String city = first.path("ort").asText(null);
        return city == null || city.isBlank() ? "Deutschland" : city;
    }

    private static String jobUrl(JsonNode j, String ref) {
        String external = j.path("externeURL").asText(null);
        if (external != null && !external.isBlank()) return external;
        return "https://www.arbeitsagentur.de/jobsuche/jobdetail/"
                + URLEncoder.encode(ref, StandardCharsets.UTF_8);
    }

    private static java.time.Instant publishedAt(JsonNode j) {
        String date = j.path("datumErsteVeroeffentlichung").asText(null);
        try {
            return date == null ? null : LocalDate.parse(date).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
