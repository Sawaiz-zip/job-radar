package com.jobradar.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobradar.config.RateLimiter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * StepStone, the largest commercial job board in Germany.
 *
 * <p>There is no public API, but the keyword landing pages ({@code /jobs/java-backend}) are
 * server-rendered and carry the whole result list as JSON in
 * {@code window.__PRELOADED_STATE__["app-unifiedResultlist"]}, and every detail page carries
 * a schema.org {@code JobPosting}. Both are read as data, not scraped out of markup.
 *
 * <p>robots.txt is the boundary, and it is honoured exactly: {@code /jobs/<keyword>} and the
 * {@code /stellenangebote--...} detail pages are allowed, while any query string other than
 * {@code ?q=} is not - which rules out {@code ?page=2}. So each keyword yields its first page
 * (25 postings) and no more; breadth comes from several specific keywords instead.
 *
 * <p>Indeed was looked at for the same role and left out on purpose: its search pages answer
 * a non-browser client with a 403 bot challenge and its RSS feed is gone (404, checked
 * 2026-10-03). Getting past that would be a bot-protection bypass, which the project rules
 * forbid.
 */
@Component
public class StepStoneAdapter extends AbstractHttpAdapter implements BoardAdapter {

    private static final String BASE = "https://www.stepstone.de";
    private static final String STATE_MARKER = "window.__PRELOADED_STATE__[\"app-unifiedResultlist\"]";

    private final ObjectMapper json = new ObjectMapper();
    private final List<String> keywords;
    private final int maxDetailFetches;

    public StepStoneAdapter(RestClient http, RateLimiter rateLimiter,
                            @Value("${jobradar.stepstone.keywords:}") List<String> keywords,
                            @Value("${jobradar.stepstone.max-detail-fetches:120}") int maxDetailFetches) {
        super(http, rateLimiter);
        this.keywords = keywords == null || keywords.isEmpty()
                ? List.of("java-entwickler", "java-backend", "backend-entwickler", "software-engineer")
                : keywords;
        this.maxDetailFetches = maxDetailFetches;
    }

    @Override
    public SourceType type() {
        return SourceType.STEPSTONE;
    }

    @Override
    public boolean supports(com.jobradar.company.Company company) {
        return false;   // board source, never bound to one company
    }

    @Override
    public FetchResult fetch(com.jobradar.company.Company company) {
        return FetchResult.failed("StepStone is a board source, not a company source", null);
    }

    /** The tag-bar terms are ignored for the same reason as BA's: the keyword list is curated. */
    @Override
    public FetchResult search(List<String> ignoredTagBarTerms) {
        List<RawJob> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();

        for (String keyword : keywords) {
            String url = BASE + "/jobs/" + keyword.trim();
            FetchResult page = call(url, String.class, html -> parseResultList(html, seen));
            if (page.isError()) {
                errors.add(keyword + ": " + page.error());
                continue;
            }
            out.addAll(page.jobs());
        }

        // Every keyword failing is a broken source; one failing is noise worth logging only.
        if (out.isEmpty() && !errors.isEmpty()) return FetchResult.failed(String.join("; ", errors), null);
        if (!errors.isEmpty()) log.warn("StepStone: {} of {} keywords failed: {}", errors.size(), keywords.size(), errors);
        return FetchResult.ok(out, 200);
    }

    FetchResult parseResultList(String html, Set<String> seen) {
        int at = html.indexOf(STATE_MARKER);
        if (at < 0) {
            // A page without the state blob is a redesign or a challenge page, never "no jobs".
            return FetchResult.failed("result list data not found - the page layout may have changed", 200);
        }
        int eq = html.indexOf('=', at + STATE_MARKER.length());
        JsonNode state;
        try {
            // Jackson stops after the first complete value, so the rest of the script is ignored.
            state = json.readTree(html.substring(eq + 1));
        } catch (Exception e) {
            return FetchResult.failed("result list data did not parse: " + e.getClass().getSimpleName(), 200);
        }

        List<RawJob> jobs = new ArrayList<>();
        for (JsonNode item : state.path("searchResults").path("items")) {
            String id = item.path("id").asText(null);
            if (id == null || !seen.add(id)) continue;
            // An anonymous ad has no employer to apply to or track, so it is not a lead.
            if (item.path("isAnonymous").asBoolean(false)) continue;
            String company = item.path("companyName").asText(null);
            if (company == null || company.isBlank()) continue;

            jobs.add(new RawJob(
                    type(), null, id,
                    item.path("title").asText(null),
                    item.path("location").asText(null),
                    null,       // filled from the detail page, only if the title passes
                    detailUrl(item.path("url").asText("")),
                    company,
                    parse(item.path("datePosted").asText(null)),
                    null));
        }
        return FetchResult.ok(jobs, 200);
    }

    @Override
    public String fetchDescription(RawJob job) {
        if (job.url() == null) return null;
        FetchResult result = call(job.url(), String.class, html -> {
            String text = jobPostingDescription(html);
            return text == null ? FetchResult.ok(List.of(), 200)
                    : FetchResult.ok(List.of(new RawJob(type(), null, job.externalId(), "", null,
                                                        text, null, null, null, null)), 200);
        });
        return result.isError() || result.jobs().isEmpty() ? null : result.jobs().get(0).descriptionText();
    }

    @Override
    public int maxDetailFetches() {
        return maxDetailFetches;
    }

    /** The schema.org JobPosting description, as plain text. */
    String jobPostingDescription(String html) {
        for (Element script : Jsoup.parse(html).select("script[type=application/ld+json]")) {
            try {
                JsonNode node = json.readTree(script.data());
                for (JsonNode candidate : node.isArray() ? node : List.of(node)) {
                    if ("JobPosting".equals(candidate.path("@type").asText())) {
                        return toText(candidate.path("description").asText(null));
                    }
                }
            } catch (Exception ignored) {
                // another ld+json block that is not ours
            }
        }
        return null;
    }

    /** Result links carry a tracking query ({@code ?rltr=...}); the canonical URL has none. */
    private static String detailUrl(String relative) {
        if (relative.isBlank()) return null;
        String path = relative.replaceFirst("\\?.*$", "");
        return path.startsWith("http") ? path : BASE + path;
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
