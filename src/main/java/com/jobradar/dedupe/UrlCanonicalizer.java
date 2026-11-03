package com.jobradar.dedupe;

import com.jobradar.source.SourceType;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Layer 1 of the dedupe ladder: every URL variant of one posting must produce one
 * canonical key. See the plan, section 6.2.
 */
@Component
public class UrlCanonicalizer {

    private static final Set<String> TRACKING_PARAMS = Set.of(
            "gh_src", "source", "ref", "refid", "trk", "trackingid", "lever-source",
            "lever-origin", "src", "mode", "utm_source", "utm_medium", "utm_campaign",
            "utm_term", "utm_content", "gclid", "fbclid");

    private static final Set<String> ID_PARAMS = Set.of("gh_jid", "jobid", "currentjobid", "id");

    private static final Pattern GREENHOUSE =
            Pattern.compile("^(?:job-)?boards(?:\\.eu)?\\.greenhouse\\.io$");
    private static final Pattern LEVER    = Pattern.compile("^jobs(?:\\.eu)?\\.lever\\.co$");
    private static final Pattern ASHBY    = Pattern.compile("^jobs\\.ashbyhq\\.com$");
    private static final Pattern SMARTREC = Pattern.compile("^(?:jobs|careers)\\.smartrecruiters\\.com$");
    private static final Pattern RECRUITEE= Pattern.compile("^([a-z0-9-]+)\\.recruitee\\.com$");
    private static final Pattern PERSONIO = Pattern.compile("^([a-z0-9-]+)\\.jobs\\.personio\\.(?:de|com)$");
    private static final Pattern WORKDAY  = Pattern.compile("^([a-z0-9-]+)\\.(wd\\d+)\\.myworkdayjobs\\.com$");
    private static final Pattern LOCALE   = Pattern.compile("^[a-z]{2}(?:-[A-Za-z]{2})?$");

    public ParsedJobUrl parse(String rawUrl) {
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException e) {
            return new ParsedJobUrl(SourceType.OTHER, null, null, null, rawUrl.trim(), rawUrl);
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) host = host.substring(4);

        List<String> segs = segments(uri.getPath());
        Map<String, String> params = queryParams(uri.getRawQuery());

        ParsedJobUrl ats = matchAts(host, segs, params, rawUrl);
        if (ats != null) return ats;

        // Unrecognised host: scrub the URL and use it as its own key.
        return new ParsedJobUrl(SourceType.OTHER, null, null, null, scrub(uri, host, segs, params), rawUrl);
    }

    private ParsedJobUrl matchAts(String host, List<String> segs, Map<String, String> params, String raw) {
        Matcher m;

        if (GREENHOUSE.matcher(host).matches()) {
            // boards.greenhouse.io/{tenant}/jobs/{id}
            int i = segs.indexOf("jobs");
            if (i > 0 && i + 1 < segs.size()) {
                return ats(SourceType.GREENHOUSE, segs.get(i - 1), null, segs.get(i + 1), raw);
            }
        }
        if (params.containsKey("gh_jid")) {
            // company.com/careers?gh_jid={id} - same posting, tenant not in the URL.
            return ats(SourceType.GREENHOUSE, null, null, params.get("gh_jid"), raw);
        }
        if (LEVER.matcher(host).matches() && segs.size() >= 2) {
            return ats(SourceType.LEVER, segs.get(0), null, segs.get(1), raw);
        }
        if (ASHBY.matcher(host).matches() && segs.size() >= 2) {
            return ats(SourceType.ASHBY, segs.get(0), null, segs.get(1), raw);
        }
        if (params.containsKey("ashby_jid")) {
            return ats(SourceType.ASHBY, null, null, params.get("ashby_jid"), raw);
        }
        if (SMARTREC.matcher(host).matches() && segs.size() >= 2) {
            return ats(SourceType.SMARTRECRUITERS, segs.get(0), null, segs.get(segs.size() - 1), raw);
        }
        if ((m = RECRUITEE.matcher(host)).matches() && segs.size() >= 2 && segs.get(0).equals("o")) {
            return ats(SourceType.RECRUITEE, m.group(1), null, segs.get(1), raw);
        }
        if ((m = PERSONIO.matcher(host)).matches()) {
            int i = segs.indexOf("job");
            if (i >= 0 && i + 1 < segs.size()) {
                return ats(SourceType.PERSONIO, m.group(1), null, digits(segs.get(i + 1)), raw);
            }
        }
        if ((m = WORKDAY.matcher(host)).matches()) {
            List<String> path = new ArrayList<>(segs);
            if (!path.isEmpty() && LOCALE.matcher(path.get(0)).matches()) path.remove(0);
            int i = path.indexOf("job");
            if (i > 0 && !path.isEmpty()) {
                String site = path.get(0);
                String req = path.get(path.size() - 1);
                return new ParsedJobUrl(SourceType.WORKDAY, m.group(1), site, req,
                        "workday:" + m.group(1) + ":" + site + ":" + req, raw);
            }
        }
        if (host.endsWith("arbeitsagentur.de")) {
            int i = segs.indexOf("jobdetail");
            if (i >= 0 && i + 1 < segs.size()) {
                return ats(SourceType.BA, null, null, segs.get(i + 1), raw);
            }
        }
        if (host.endsWith("arbeitnow.com") && segs.size() >= 2 && segs.get(0).equals("jobs")) {
            return ats(SourceType.ARBEITNOW, null, null, segs.get(segs.size() - 1), raw);
        }
        if (host.endsWith("linkedin.com")) {
            // Never fetched. The id alone is enough to recognise the same link again.
            String id = params.get("currentjobid");
            if (id == null) {
                int i = segs.indexOf("view");
                if (i >= 0 && i + 1 < segs.size()) id = segs.get(i + 1);
            }
            if (id != null) return ats(SourceType.LINKEDIN_LINK, null, null, id, raw);
        }
        return null;
    }

    private ParsedJobUrl ats(SourceType type, String tenant, String site, String id, String raw) {
        String key = type.name().toLowerCase(Locale.ROOT) + ":" + (tenant == null ? "" : tenant) + ":" + id;
        return new ParsedJobUrl(type, tenant, site, id, key, raw);
    }

    /** Scrub an unrecognised URL: lowercase host, no www/fragment/trailing slash, no tracking params. */
    private String scrub(URI uri, String host, List<String> segs, Map<String, String> params) {
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
        List<String> kept = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(params).entrySet()) {
            if (TRACKING_PARAMS.contains(e.getKey())) continue;
            if (!ID_PARAMS.contains(e.getKey()) && e.getValue().isEmpty()) continue;
            kept.add(e.getKey() + "=" + e.getValue());
        }
        // /apply is the same posting as the posting itself (Lever, Greenhouse).
        List<String> path = new ArrayList<>(segs);
        if (!path.isEmpty() && path.get(path.size() - 1).equals("apply")) path.remove(path.size() - 1);

        String s = scheme + "://" + host + (path.isEmpty() ? "" : "/" + String.join("/", path));
        return kept.isEmpty() ? s : s + "?" + String.join("&", kept);
    }

    private static List<String> segments(String path) {
        if (path == null || path.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : path.split("/")) if (!p.isBlank()) out.add(p);
        return out;
    }

    private static Map<String, String> queryParams(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) continue;
            int eq = pair.indexOf('=');
            String k = (eq < 0 ? pair : pair.substring(0, eq)).toLowerCase(Locale.ROOT);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static String digits(String s) {
        String d = s.replaceAll("\\D", "");
        return d.isEmpty() ? s : d;
    }
}
