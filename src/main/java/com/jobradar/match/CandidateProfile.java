package com.jobradar.match;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads the candidate profile ({@code jobradar.profile-path}, see
 * {@code config/profile.example.yaml}) so AI scoring is grounded in a real CV. The file is
 * only read, never written, so it can be shared with a CV generator that uses the same
 * format. Without one, AI scoring falls back to judging postings on the filters alone.
 */
@Component
public class CandidateProfile {

    private static final Logger log = LoggerFactory.getLogger(CandidateProfile.class);

    private final String summary;
    private final boolean loaded;

    public CandidateProfile(@Value("${jobradar.profile-path:}") String profilePath) {
        String built = null;
        try {
            Path p = Path.of(profilePath);
            if (!profilePath.isBlank() && Files.exists(p)) {
                built = build(new Yaml().load(Files.readString(p)));
                log.info("loaded candidate profile from {}", p.toAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("could not read profile.yaml ({}), scoring will use the fallback summary", e.toString());
        }
        this.loaded = built != null;
        this.summary = built != null ? built : fallback();
    }

    public String summary() {
        return summary;
    }

    public boolean isLoaded() {
        return loaded;
    }

    @SuppressWarnings("unchecked")
    private static String build(Map<String, Object> y) {
        StringBuilder sb = new StringBuilder();
        Map<String, Object> tailoring = (Map<String, Object>) y.getOrDefault("tailoring", Map.of());

        sb.append("Identity: ").append(tailoring.getOrDefault("primary_identity", "Not stated")).append("\n");
        sb.append("Summary: ").append(str(y.get("summary_seed"))).append("\n");

        sb.append("Experience:\n");
        for (Map<String, Object> job : (List<Map<String, Object>>) y.getOrDefault("employment", List.of())) {
            sb.append("  - ").append(str(job.get("title"))).append(" at ").append(str(job.get("company")))
              .append(" (").append(str(job.get("start_date"))).append(" - ")
              .append(str(job.get("end_date"))).append(")\n");
        }

        sb.append("Skills:\n");
        for (Map<String, Object> group : (List<Map<String, Object>>) y.getOrDefault("skills", List.of())) {
            Object items = group.get("items");
            String list = items instanceof List<?> l
                    ? String.join(", ", l.stream().map(CandidateProfile::str).toList())
                    : str(items);
            sb.append("  - ").append(str(group.get("category"))).append(": ").append(list).append("\n");
        }

        sb.append("Education:\n");
        for (Map<String, Object> ed : (List<Map<String, Object>>) y.getOrDefault("education", List.of())) {
            sb.append("  - ").append(str(ed.get("degree"))).append(", ")
              .append(str(ed.get("institution"))).append(" (").append(str(ed.get("dates"))).append(")\n");
        }
        return sb.toString();
    }

    private static String fallback() {
        return """
               No candidate profile is configured (jobradar.profile-path). Judge fit from the
               job title and the scoring rules only, and keep fit_score at or below 50.
               """;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }
}
