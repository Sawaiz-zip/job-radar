package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.config.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Arbeitnow publishes one paginated feed of English-friendly German jobs, fed from company
 * ATSs. There is no search parameter, so the keywords are applied here rather than server
 * side - the feed is small enough that a few pages cover the recent postings.
 */
@Component
public class ArbeitnowAdapter extends AbstractHttpAdapter implements BoardAdapter {

    private static final String API = "https://www.arbeitnow.com/api/job-board-api?page=%d";
    private static final int MAX_PAGES = 4;

    public ArbeitnowAdapter(RestClient http, RateLimiter rateLimiter) {
        super(http, rateLimiter);
    }

    @Override
    public SourceType type() {
        return SourceType.ARBEITNOW;
    }

    @Override
    public boolean supports(com.jobradar.company.Company company) {
        return false;   // board source, never bound to one company
    }

    @Override
    public FetchResult fetch(com.jobradar.company.Company company) {
        return FetchResult.failed("arbeitnow is a board source, not a company source", null);
    }

    @Override
    public FetchResult search(List<String> queries) {
        List<RawJob> all = new ArrayList<>();
        Integer status = null;
        String error = null;

        for (int page = 1; page <= MAX_PAGES; page++) {
            FetchResult result = call(API.formatted(page), JsonNode.class, root -> {
                List<RawJob> jobs = new ArrayList<>();
                for (JsonNode j : root.path("data")) {
                    String title = j.path("title").asText(null);
                    if (title == null || !matches(title, queries)) continue;
                    long created = j.path("created_at").asLong(0);
                    jobs.add(new RawJob(
                            type(), null,
                            j.path("slug").asText(null),
                            title,
                            j.path("location").asText(null),
                            toText(j.path("description").asText(null)),
                            j.path("url").asText(null),
                            j.path("company_name").asText(null),
                            created > 0 ? Instant.ofEpochSecond(created) : null,
                            null));
                }
                return FetchResult.ok(jobs, 200);
            });
            if (result.isError()) {
                error = result.error();
                status = result.httpStatus();
                break;
            }
            status = 200;
            all.addAll(result.jobs());
            if (result.jobs().isEmpty() && page > 1) break;
        }
        return error != null && all.isEmpty() ? FetchResult.failed(error, status) : FetchResult.ok(all, 200);
    }

    /** Keyword match on the title, since the feed has no search parameter. */
    static boolean matches(String title, List<String> queries) {
        if (queries == null || queries.isEmpty()) return true;
        String t = title.toLowerCase(Locale.ROOT);
        return queries.stream().anyMatch(q -> t.contains(q.toLowerCase(Locale.ROOT)));
    }
}
