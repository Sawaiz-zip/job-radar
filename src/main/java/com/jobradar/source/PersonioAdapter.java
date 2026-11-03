package com.jobradar.source;

import com.jobradar.company.Company;
import com.jobradar.config.RateLimiter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Personio publishes an XML feed per tenant. Very common in German SMEs, which is
 * most of the mid-sized companies on the watchlist.
 */
@Component
public class PersonioAdapter extends AbstractHttpAdapter {

    private static final String DE = "https://%s.jobs.personio.de/xml?language=en";
    private static final String COM = "https://%s.jobs.personio.com/xml?language=en";

    public PersonioAdapter(RestClient http, RateLimiter rateLimiter) {
        super(http, rateLimiter);
    }

    @Override
    public SourceType type() {
        return SourceType.PERSONIO;
    }

    @Override
    public boolean supports(Company c) {
        return c.atsType() == SourceType.PERSONIO && c.atsTenant() != null;
    }

    @Override
    public FetchResult fetch(Company c) {
        FetchResult first = fetchFrom(DE.formatted(c.atsTenant()), c);
        // Some tenants live on .com instead; the .de host 404s for them.
        if (first.isError() || first.jobs().isEmpty()) {
            FetchResult second = fetchFrom(COM.formatted(c.atsTenant()), c);
            if (!second.isError() && !second.jobs().isEmpty()) return second;
        }
        // The XML feed 404s on a board with nothing open, which made beyonnex look broken
        // for eleven scans in a row. The board's own search endpoint tells the two apart:
        // a 200 with an empty list is a real, empty board.
        if (first.isError() && Integer.valueOf(404).equals(first.httpStatus())
                && boardIsEmpty(c.atsTenant())) {
            return FetchResult.ok(List.of(), 200);
        }
        return first;
    }

    private boolean boardIsEmpty(String tenant) {
        FetchResult probe = call("https://%s.jobs.personio.de/search.json".formatted(tenant), String.class,
                body -> body.trim().equals("[]") ? FetchResult.ok(List.of(), 200)
                                                 : FetchResult.failed("not empty", 200));
        return !probe.isError();
    }

    private FetchResult fetchFrom(String url, Company c) {
        // Read as bytes: the feed is UTF-8 but is served as "text/xml" with no charset, and
        // Spring then decodes a String body as ISO-8859-1 - "Müller" arrived as "MÃ¼ller" in
        // every Personio job, which also hid "fließend Deutsch" from the language filter.
        return call(url, byte[].class, bytes -> {
            List<RawJob> jobs = new ArrayList<>();
            var doc = Jsoup.parse(new String(bytes, StandardCharsets.UTF_8), "", Parser.xmlParser());
            for (Element p : doc.select("position")) {
                String id = text(p, "id");
                if (id == null) continue;
                String office = text(p, "office");
                StringBuilder desc = new StringBuilder();
                for (Element d : p.select("jobDescription")) {
                    String name = text(d, "name");
                    String value = text(d, "value");
                    if (name != null) desc.append(name).append(": ");
                    if (value != null) desc.append(Jsoup.parse(value).text()).append("\n");
                }
                jobs.add(new RawJob(
                        type(), c.atsTenant(), id,
                        text(p, "name"),
                        office,
                        desc.isEmpty() ? null : desc.toString().trim(),
                        "https://%s.jobs.personio.de/job/%s".formatted(c.atsTenant(), id),
                        c.name(),
                        parse(text(p, "createdAt")),
                        null));
            }
            return FetchResult.ok(jobs, 200);
        });
    }

    private static String text(Element parent, String tag) {
        Element e = parent.selectFirst("> " + tag);
        if (e == null) e = parent.selectFirst(tag);
        return e == null || e.text().isBlank() ? null : e.text();
    }

    private static Instant parse(String s) {
        try {
            return s == null ? null : OffsetDateTime.parse(s).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
