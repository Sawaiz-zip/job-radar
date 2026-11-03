package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Component
public class RecruiteeAdapter extends AbstractHttpAdapter {

    private static final String API = "https://%s.recruitee.com/api/offers/";
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'");

    public RecruiteeAdapter(RestClient http, RateLimiter rateLimiter) {
        super(http, rateLimiter);
    }

    @Override
    public SourceType type() {
        return SourceType.RECRUITEE;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.RECRUITEE && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company c) {
        return call(API.formatted(c.atsTenant()), JsonNode.class, root -> {
            List<RawJob> jobs = new ArrayList<>();
            for (JsonNode j : root.path("offers")) {
                jobs.add(new RawJob(
                        type(), c.atsTenant(),
                        j.path("id").asText(null),
                        j.path("title").asText(null),
                        j.path("location").asText(null),
                        toText(j.path("description").asText(null)),
                        j.path("careers_url").asText(null),
                        c.name(),
                        parse(j.path("created_at").asText(null)),
                        null));
            }
            return FetchResult.ok(jobs, 200);
        });
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : LocalDateTime.parse(s, TS).toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
