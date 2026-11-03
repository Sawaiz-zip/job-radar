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
public class GreenhouseAdapter extends AbstractHttpAdapter {

    private static final String API = "https://boards-api.greenhouse.io/v1/boards/%s/jobs?content=true";

    public GreenhouseAdapter(RestClient http, RateLimiter rateLimiter) {
        super(http, rateLimiter);
    }

    @Override
    public SourceType type() {
        return SourceType.GREENHOUSE;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.GREENHOUSE && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company c) {
        return call(API.formatted(c.atsTenant()), JsonNode.class, root -> {
            List<RawJob> jobs = new ArrayList<>();
            for (JsonNode j : root.path("jobs")) {
                jobs.add(new RawJob(
                        type(),
                        c.atsTenant(),
                        j.path("id").asText(null),
                        j.path("title").asText(null),
                        j.path("location").path("name").asText(null),
                        toText(j.path("content").asText(null)),
                        j.path("absolute_url").asText(null),
                        c.name(),
                        // Greenhouse exposes updated_at, not a true posting date.
                        parse(j.path("updated_at").asText(null)),
                        null));
            }
            return FetchResult.ok(jobs, 200);
        });
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
