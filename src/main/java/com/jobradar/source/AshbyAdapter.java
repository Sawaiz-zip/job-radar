package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class AshbyAdapter extends AbstractHttpAdapter {

    private static final String API =
            "https://api.ashbyhq.com/posting-api/job-board/%s?includeCompensation=true";

    public AshbyAdapter(RestClient http, RateLimiter rateLimiter) {
        super(http, rateLimiter);
    }

    @Override
    public SourceType type() {
        return SourceType.ASHBY;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.ASHBY && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company c) {
        return call(API.formatted(c.atsTenant()), JsonNode.class, root -> {
            List<RawJob> jobs = new ArrayList<>();
            for (JsonNode j : root.path("jobs")) {
                if (j.has("isListed") && !j.path("isListed").asBoolean(true)) continue;
                jobs.add(new RawJob(
                        type(), c.atsTenant(),
                        j.path("id").asText(null),
                        j.path("title").asText(null),
                        location(j),
                        j.hasNonNull("descriptionPlain")
                                ? j.path("descriptionPlain").asText()
                                : toText(j.path("descriptionHtml").asText(null)),
                        j.path("jobUrl").asText(null),
                        c.name(),
                        parse(j.path("publishedAt").asText(null)),
                        null));
            }
            return FetchResult.ok(jobs, 200);
        });
    }

    private static String location(JsonNode j) {
        String city = j.path("address").path("postalAddress").path("addressLocality").asText(null);
        String loc = j.path("location").asText(null);
        if (city != null && !city.isBlank()) return city;
        return loc;
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
