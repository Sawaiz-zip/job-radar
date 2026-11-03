package com.jobradar.dedupe;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer.Form;
import java.util.*;
import java.util.regex.Pattern;

/** Title / city / company normalization for fingerprints (plan section 6.3). */
@Component
public class Normalizer {

    /** (m/w/d) and its many cousins, which German postings append to almost every title. */
    private static final Pattern GENDER = Pattern.compile(
            // Each slot is a letter or one of the spelled-out words German ads use:
            // (m/w/div.), (m/w/divers), (w/m/x). An earlier version only accepted
            // single letters and let "Backend Developer (m/w/div.)" through.
            "\\(\\s*(?:[mwfdxa]|div\\.?|divers|all)"
          + "(?:\\s*[/|]\\s*(?:[mwfdxa]|div\\.?|divers|all))+\\s*\\)"
          + "|\\(\\s*all\\s+genders?\\s*\\)"
          + "|\\(\\s*gn\\s*\\)"
          + "|\\bm/w/d(?:iv\\.?|ivers)?\\b|\\bw/m/d\\b|\\bf/m/x\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENIORITY = Pattern.compile(
            "\\b(senior|junior|jr|sr|lead|principal|staff|werkstudent|intern|praktikant)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern LEGAL_FORM = Pattern.compile(
            "\\b(gmbh\\s*&\\s*co\\.?\\s*kg|gmbh|mbh|ag|se|kg|ohg|e\\.?v\\.?|inc\\.?|ltd\\.?|llc|plc|b\\.?v\\.?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> CITY_ALIASES = Map.ofEntries(
            Map.entry("muenchen", "munich"), Map.entry("munchen", "munich"),
            Map.entry("koeln", "cologne"), Map.entry("koln", "cologne"),
            Map.entry("frankfurt am main", "frankfurt"),
            Map.entry("frankfurt a m", "frankfurt"),
            Map.entry("nuernberg", "nuremberg"), Map.entry("nurnberg", "nuremberg"),
            Map.entry("hannover", "hanover"),
            Map.entry("braunschweig", "brunswick"),
            Map.entry("wien", "vienna"), Map.entry("zuerich", "zurich"));

    /** Title as displayed, minus gender tags - used for display and for search. */
    public String cleanTitle(String title) {
        if (title == null) return "";
        return collapse(GENDER.matcher(title).replaceAll(" ")
                .replace("*", " ")
                .replaceAll("[\\-–—,:|]+\\s*$", ""));
    }

    /** Title for fingerprinting: also drops seniority, since a repost may change it. */
    public String normalizeTitle(String title) {
        String t = asciiFold(cleanTitle(title)).toLowerCase(Locale.ROOT);
        t = SENIORITY.matcher(t).replaceAll(" ");
        t = t.replaceAll("[^a-z0-9]+", " ");
        return collapse(t);
    }

    public String normalizeCompany(String name) {
        if (name == null) return "";
        String n = asciiFold(name).toLowerCase(Locale.ROOT);
        n = LEGAL_FORM.matcher(n).replaceAll(" ");
        n = n.replaceAll("[^a-z0-9]+", " ");
        return collapse(n);
    }

    /** One or more cities to a stable, sorted key. Remote work collapses to remote-de. */
    public String normalizeCity(String location) {
        if (location == null || location.isBlank()) return "";
        String l = asciiFold(location).toLowerCase(Locale.ROOT);
        if (l.contains("remote") || l.contains("homeoffice") || l.contains("home office")) {
            if (!l.matches(".*\\b(berlin|munich|hamburg|cologne|frankfurt)\\b.*")) return "remote-de";
        }
        List<String> parts = new ArrayList<>();
        for (String raw : l.split("[,;/|]|\\band\\b|\\boder\\b")) {
            String p = collapse(raw.replaceAll("[^a-z0-9 ]+", " "));
            if (p.isBlank()) continue;
            p = CITY_ALIASES.getOrDefault(p, p);
            // Drop country-level noise so "Berlin, Germany" and "Berlin" agree.
            if (p.equals("germany") || p.equals("deutschland") || p.equals("de")) continue;
            parts.add(p);
        }
        Collections.sort(parts);
        return String.join("+", parts);
    }

    /** Layer 3 key: one open job per (company, title, city). */
    public String fingerprint(long companyId, String title, String location) {
        return sha256(companyId + "|" + normalizeTitle(title) + "|" + normalizeCity(location));
    }

    public String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String asciiFold(String s) {
        String x = s.replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                    .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                    .replace("ß", "ss");
        return java.text.Normalizer.normalize(x, Form.NFKD).replaceAll("\\p{M}", "");
    }

    private static String collapse(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
