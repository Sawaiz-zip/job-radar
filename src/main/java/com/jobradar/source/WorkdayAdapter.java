package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import com.jobradar.match.HardFilters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Workday, which most large corporates run and which has no documented API.
 *
 * <p>The search endpoint is a POST that the career site's own front end calls, and it
 * needs three things: the tenant, the {@code wdN} data centre, and the site name. The
 * site name is the awkward one - it is arbitrary per customer ("NVIDIAExternalCareerSite",
 * "SAPCareers"), so it cannot be guessed and must come from the careers URL, which is why
 * a Workday company is only scannable once detection has stored {@code ats_site}.
 *
 * <p>The list carries no description, so each posting needs a second call. That is capped
 * per scan, exactly as the BA adapter caps its detail fetches.
 */
@Component
public class WorkdayAdapter extends AbstractHttpAdapter {

    private static final String SEARCH = "https://%s.%s.myworkdayjobs.com/wday/cxs/%s/%s/jobs";
    private static final String DETAIL = "https://%s.%s.myworkdayjobs.com/wday/cxs/%s/%s%s";
    private static final int PAGE = 20;

    private final ObjectMapper json = new ObjectMapper();
    private final HardFilters filters;
    private final int maxPages;
    private final int maxDetails;

    public WorkdayAdapter(RestClient http, RateLimiter rateLimiter, HardFilters filters,
                          @Value("${jobradar.workday.max-pages:5}") int maxPages,
                          @Value("${jobradar.workday.max-detail-fetches:60}") int maxDetails) {
        super(http, rateLimiter);
        this.filters = filters;
        this.maxPages = maxPages;
        this.maxDetails = maxDetails;
    }

    @Override
    public SourceType type() {
        return SourceType.WORKDAY;
    }

    @Override
    public boolean supports(Company c) {
        // ats_region holds the wdN data centre; without the site name there is nothing to call.
        return c.atsType() == SourceType.WORKDAY
                && c.atsTenant() != null && c.atsSite() != null;
    }

    @Override
    public FetchResult fetch(Company company) {
        String tenant = company.atsTenant();
        String dc = dataCentre(company);
        String site = company.atsSite();

        List<RawJob> jobs = new ArrayList<>();
        String error = null;
        int details = 0;

        for (int page = 0; page < maxPages; page++) {
            String url = SEARCH.formatted(tenant, dc, tenant, site);
            JsonNode body = post(url, Map.of(
                    "appliedFacets", Map.of(),
                    "limit", PAGE,
                    "offset", page * PAGE,
                    "searchText", ""));
            if (body == null) {
                if (page == 0) error = "Workday search failed or is blocked for this tenant";
                break;
            }
            JsonNode postings = body.path("jobPostings");
            if (!postings.isArray() || postings.isEmpty()) break;

            for (JsonNode p : postings) {
                String path = p.path("externalPath").asText(null);
                if (path == null) continue;
                // Same rule as the other detail-fetching adapters: spend the budget only
                // on titles that can survive the rules.
                String title = p.path("title").asText(null);
                String description = null;
                if (details < maxDetails && filters.evaluate(title, null).keep()) {
                    description = description(tenant, dc, site, path);
                    details++;
                }
                jobs.add(new RawJob(
                        type(), tenant,
                        p.path("bulletFields").path(0).asText(p.path("id").asText(null)),
                        title,
                        p.path("locationsText").asText(null),
                        description,
                        "https://%s.%s.myworkdayjobs.com/%s%s".formatted(tenant, dc, site, path),
                        company.name(),
                        postedOn(p.path("postedOn").asText(null)),
                        null));
            }
            if (postings.size() < PAGE) break;
        }

        if (jobs.isEmpty() && error != null) return FetchResult.failed(error, null);
        return FetchResult.ok(jobs, 200);
    }

    private String description(String tenant, String dc, String site, String path) {
        String url = DETAIL.formatted(tenant, dc, tenant, site, path);
        rateLimiter.acquire(url);
        try {
            String raw = http.get().uri(url).header("Accept", "application/json")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                    .body(String.class);
            if (raw == null || raw.isBlank()) return null;
            return toText(json.readTree(raw).path("jobPostingInfo").path("jobDescription").asText(null));
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode post(String url, Map<String, Object> body) {
        rateLimiter.acquire(url);
        try {
            String raw = http.post().uri(url)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .body(json.writeValueAsString(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                    .body(String.class);
            if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) return null;
            return json.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    /** Tenants live in one of Workday's numbered data centres; wd3 is the common default. */
    private static String dataCentre(Company c) {
        String region = c.atsRegion();
        return region == null || region.isBlank() ? "wd3" : region;
    }

    private static Instant postedOn(String text) {
        // Workday reports "Posted 5 Days Ago", which is not a date worth parsing badly.
        if (text == null) return null;
        try {
            return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
