package com.jobradar.source;

import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import com.jobradar.match.HardFilters;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SAP SuccessFactors career sites (the "RMK" flavour behind jobs.sap.com and many German
 * corporates).
 *
 * <p>The only HTML adapter here, because SuccessFactors publishes no feed and no JSON: the
 * listing is server-rendered and the detail pages carry no schema.org data either, so both
 * have to be parsed. That makes it the most fragile source in the system, which is why it
 * reports a failure rather than an empty result when the markup stops matching - a silent
 * zero from a site that normally returns hundreds is exactly the breakage the health rules
 * exist to catch.
 *
 * <p>The tenant here is the career site's host, e.g. {@code jobs.sap.com}, because these
 * sites have no tenant slug of their own.
 */
@Component
public class SuccessFactorsAdapter extends AbstractHttpAdapter {

    private static final int PAGE = 25;

    private final HardFilters filters;
    private final int maxPages;
    private final int maxDetails;
    private final String locationFilter;

    public SuccessFactorsAdapter(RestClient http, RateLimiter rateLimiter, HardFilters filters,
                                 @Value("${jobradar.successfactors.max-pages:4}") int maxPages,
                                 @Value("${jobradar.successfactors.max-detail-fetches:50}") int maxDetails,
                                 @Value("${jobradar.successfactors.location:Germany}") String locationFilter) {
        super(http, rateLimiter);
        this.filters = filters;
        this.maxPages = maxPages;
        this.maxDetails = maxDetails;
        this.locationFilter = locationFilter;
    }

    @Override
    public SourceType type() {
        return SourceType.SUCCESSFACTORS;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.SUCCESSFACTORS && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company company) {
        String host = company.atsTenant().replaceFirst("^https?://", "").replaceAll("/.*$", "");
        List<RawJob> jobs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int details = 0;
        boolean anyPageLoaded = false;

        for (int page = 0; page < maxPages; page++) {
            String url = "https://%s/search/?q=&locationsearch=%s&startrow=%d".formatted(
                    host, URLEncoder.encode(locationFilter, StandardCharsets.UTF_8), page * PAGE);
            String html = get(url);
            if (html == null) break;
            anyPageLoaded = true;

            Document doc = Jsoup.parse(html, "https://" + host);
            var links = doc.select("a.jobTitle-link, a.jobTitle, td.colTitle a");
            if (links.isEmpty()) break;

            int before = jobs.size();
            for (Element link : links) {
                String href = link.absUrl("href");
                if (href.isBlank() || !seen.add(href)) continue;

                // One request per posting, so spend them only on titles that can survive
                // the rules. Otherwise the cap is exhausted on roles that get filtered
                // anyway and the ones worth reading arrive with no description at all.
                String title = link.text().trim();
                String description = null;
                if (details < maxDetails && filters.evaluate(title, null).keep()) {
                    description = description(href);
                    details++;
                }
                jobs.add(new RawJob(
                        type(), host, externalId(href),
                        title,
                        locationOf(link),
                        description,
                        href,
                        company.name(),
                        null,          // these sites print "Date: 17 Sep 2026" inconsistently
                        null));
            }
            if (jobs.size() == before) break;
        }

        if (!anyPageLoaded) {
            return FetchResult.failed("SuccessFactors career site did not load", null);
        }
        if (jobs.isEmpty()) {
            // Never report a clean zero from a site that normally lists hundreds: that is a
            // markup change, not an empty board, and it should be visible as a failure.
            return FetchResult.failed(
                    "career site loaded but no job links matched - the markup may have changed", 200);
        }
        return FetchResult.ok(jobs, 200);
    }

    /** The detail page has no structured data, so the description container is read directly. */
    private String description(String url) {
        String html = get(url);
        if (html == null) return null;
        Document doc = Jsoup.parse(html);
        Element body = doc.selectFirst(".jobDescription, .job-description, #job-description, .jobdescription");
        if (body == null) return null;
        String text = body.text();
        return text.isBlank() ? null : text;
    }

    private static String locationOf(Element link) {
        Element row = link.closest("tr");
        if (row != null) {
            Element loc = row.selectFirst(".jobLocation, .colLocation");
            if (loc != null && !loc.text().isBlank()) return loc.text().trim();
        }
        return null;
    }

    /** The trailing numeric id in /job/Berlin-Some-Role-10557/1234567/ style URLs. */
    private static String externalId(String href) {
        String[] parts = href.replaceAll("/+$", "").split("/");
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i].matches("\\d{3,}")) return parts[i];
        }
        return href;
    }

    private String get(String url) {
        rateLimiter.acquire(url);
        try {
            String body = http.get().uri(url)
                    .header("Accept", "text/html")
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> { })
                    .body(String.class);
            return body == null || body.isBlank() ? null : body;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
