package com.jobradar.source;

import com.jobradar.config.RateLimiter;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Function;

/** Shared fetch-and-classify behaviour: rate limit, call, turn failures into data. */
public abstract class AbstractHttpAdapter implements JobSourceAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final RestClient http;
    protected final RateLimiter rateLimiter;

    protected AbstractHttpAdapter(RestClient http, RateLimiter rateLimiter) {
        this.http = http;
        this.rateLimiter = rateLimiter;
    }

    protected <T> FetchResult call(String url, Class<T> bodyType, Function<T, FetchResult> mapper) {
        rateLimiter.acquire(url);
        try {
            T body = http.get().uri(url).retrieve().body(bodyType);
            if (body == null) return FetchResult.failed("empty body", 200);
            return mapper.apply(body);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // On a per-company ATS a 404 almost always means the tenant is gone - the
                // employer moved to another ATS or renamed its board - not a transient error.
                return FetchResult.failed("404 not found - for a company board this usually means "
                        + "it moved to another ATS; re-run detection or edit its board", 404);
            }
            return FetchResult.failed(e.getStatusCode() + " " + trim(e.getMessage()),
                    e.getStatusCode().value());
        } catch (RuntimeException e) {
            return FetchResult.failed(e.getClass().getSimpleName() + ": " + trim(e.getMessage()), null);
        }
    }

    /** Job descriptions arrive as HTML from most ATSs; store readable text. */
    protected static String toText(String html) {
        if (html == null || html.isBlank()) return null;
        return Jsoup.parse(html).text();
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
