package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import com.jobradar.match.HardFilters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * SmartRecruiters' public posting API - Bosch, Delivery Hero-style corporates, and since the
 * acquisition, parts of SAP.
 *
 * <p>The type and the detector rule existed long before this adapter did, so a company
 * detected as SmartRecruiters used to sit in the registry and never be scanned. The list is
 * filtered to Germany at the API ({@code country=de}): Bosch alone has close to five thousand
 * postings worldwide, and pulling all of them to throw most away is not polite.
 *
 * <p>The list has no description, so - as with Workday and BA - a detail call is spent only
 * on titles that already passed the rules, and capped per scan.
 */
@Component
public class SmartRecruitersAdapter extends AbstractHttpAdapter {

    private static final String LIST =
            "https://api.smartrecruiters.com/v1/companies/%s/postings?country=de&limit=%d&offset=%d";
    private static final String DETAIL = "https://api.smartrecruiters.com/v1/companies/%s/postings/%s";
    private static final int PAGE = 100;

    private final HardFilters filters;
    private final int maxPages;
    private final int maxDetails;

    public SmartRecruitersAdapter(RestClient http, RateLimiter rateLimiter, HardFilters filters,
                                  @Value("${jobradar.smartrecruiters.max-pages:10}") int maxPages,
                                  @Value("${jobradar.smartrecruiters.max-detail-fetches:60}") int maxDetails) {
        super(http, rateLimiter);
        this.filters = filters;
        this.maxPages = maxPages;
        this.maxDetails = maxDetails;
    }

    @Override
    public SourceType type() {
        return SourceType.SMARTRECRUITERS;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.SMARTRECRUITERS && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company c) {
        List<RawJob> jobs = new ArrayList<>();
        int details = 0;

        for (int page = 0; page < maxPages; page++) {
            JsonNode[] body = new JsonNode[1];
            FetchResult result = call(LIST.formatted(c.atsTenant(), PAGE, page * PAGE),
                    JsonNode.class, root -> {
                        body[0] = root;
                        return FetchResult.ok(List.of(), 200);
                    });
            if (result.isError()) {
                // A failure after page one still leaves real jobs worth keeping.
                if (page == 0) return result;
                break;
            }
            JsonNode content = body[0].path("content");
            if (!content.isArray() || content.isEmpty()) break;

            for (JsonNode p : content) {
                String id = p.path("id").asText(null);
                if (id == null) continue;
                String title = p.path("name").asText(null);
                String description = null;
                if (details < maxDetails && filters.evaluate(title, null).keep()) {
                    description = description(c.atsTenant(), id);
                    details++;
                }
                jobs.add(new RawJob(
                        type(), c.atsTenant(), id, title,
                        p.path("location").path("fullLocation").asText(
                                p.path("location").path("city").asText(null)),
                        description,
                        "https://jobs.smartrecruiters.com/%s/%s".formatted(c.atsTenant(), id),
                        c.name(),
                        parse(p.path("releasedDate").asText(null)),
                        null));
            }
            if (content.size() < PAGE) break;
        }
        return FetchResult.ok(jobs, 200);
    }

    private String description(String tenant, String postingId) {
        String url = DETAIL.formatted(tenant, postingId);
        rateLimiter.acquire(url);
        try {
            JsonNode sections = http.get().uri(url).retrieve().body(JsonNode.class)
                                    .path("jobAd").path("sections");
            StringBuilder out = new StringBuilder();
            for (String key : List.of("jobDescription", "qualifications", "additionalInformation")) {
                String text = toText(sections.path(key).path("text").asText(null));
                if (text != null) out.append(text).append("\n\n");
            }
            return out.isEmpty() ? null : out.toString().trim();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
