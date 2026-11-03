package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class LeverAdapter extends AbstractHttpAdapter {

    private static final String API = "https://api.lever.co/v0/postings/%s?mode=json";

    public LeverAdapter(RestClient http, RateLimiter rateLimiter) {
        super(http, rateLimiter);
    }

    @Override
    public SourceType type() {
        return SourceType.LEVER;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.LEVER && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company c) {
        return call(API.formatted(c.atsTenant()), JsonNode.class, root -> {
            List<RawJob> jobs = new ArrayList<>();
            for (JsonNode j : root) {
                long created = j.path("createdAt").asLong(0);
                jobs.add(new RawJob(
                        type(),
                        c.atsTenant(),
                        j.path("id").asText(null),
                        j.path("text").asText(null),
                        j.path("categories").path("location").asText(null),
                        j.hasNonNull("descriptionPlain")
                                ? j.path("descriptionPlain").asText()
                                : toText(j.path("description").asText(null)),
                        j.path("hostedUrl").asText(null),
                        c.name(),
                        created > 0 ? Instant.ofEpochMilli(created) : null,
                        null));
            }
            return FetchResult.ok(jobs, 200);
        });
    }
}
