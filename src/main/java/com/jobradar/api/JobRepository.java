package com.jobradar.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Repository
public class JobRepository {

    private final JdbcClient db;

    public JobRepository(JdbcClient db) {
        this.db = db;
    }

    /** Shared projection: the job list and the per-company view must show the same fields. */
    private static final String BASE_SELECT = """
            SELECT j.id, c.name AS company, j.title, j.location_raw, j.apply_url,
                   j.posted_at, j.first_seen_at, j.state, j.reposted, j.cv_generated,
                   j.application_status, m.fit_score, m.chance_score, m.reason, m.state AS match_state,
                   m.years_required, m.german_level,
                   (SELECT string_agg(DISTINCT s.source_type, ',') FROM job_source s WHERE s.job_id = j.id) AS sources
              FROM job j
              JOIN company c ON c.id = j.company_id
              LEFT JOIN job_match m ON m.job_id = j.id
             WHERE 1 = 1
            """;

    /** A page of the job list plus how many jobs matched in all, so the UI can say "300 of 1,240". */
    public record Page(List<JobView> jobs, long total) {}

    /**
     * The job list, narrowed by every filter at once. One WHERE clause is built and used for
     * both the page and the total, so the count can never disagree with the list.
     */
    public Page search(JobFilter f) {
        List<Object> params = new ArrayList<>();
        String where = where(f, params);

        // Tags are matched in Java (see TagMatcher): fetch the titles the other filters
        // allow, keep the ids whose title matches, and page over exactly those.
        TagMatcher tags = new TagMatcher(f.terms());
        if (!tags.isEmpty()) {
            List<Long> ids = new ArrayList<>();
            db.sql("SELECT j.id, j.title" + FROM + " WHERE 1 = 1" + where)
              .params(params)
              .query((rs, n) -> {
                  if (tags.matches(rs.getString("title"))) ids.add(rs.getLong("id"));
                  return null;
              }).list();
            if (ids.isEmpty()) return new Page(List.of(), 0);
            where += " AND j.id = ANY(?)";
            params.add(ids.toArray(Long[]::new));
        }

        long total = db.sql("SELECT count(*)" + FROM + " WHERE 1 = 1" + where)
                       .params(params).query(Long.class).single();

        StringBuilder sql = new StringBuilder(BASE_SELECT).append(where);
        sql.append(switch (f.sort() == null ? "date" : f.sort()) {
            case "chance" -> " ORDER BY m.chance_score DESC NULLS LAST, COALESCE(j.posted_at, j.first_seen_at) DESC";
            case "fit"    -> " ORDER BY m.fit_score DESC NULLS LAST, COALESCE(j.posted_at, j.first_seen_at) DESC";
            case "company"-> " ORDER BY c.name, j.title";
            default       -> " ORDER BY COALESCE(j.posted_at, j.first_seen_at) DESC";
        });
        // A stable tiebreak: without it two equal rows may swap between requests, which
        // reads as the list "jumping" when nothing changed.
        sql.append(", j.id DESC LIMIT ?");
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(Math.max(1, Math.min(f.limit(), 1000)));

        List<JobView> rows = db.sql(sql.toString()).params(pageParams).query(JobRepository::mapJob).list();
        return new Page(rows, total);
    }

    private static final String FROM = " FROM job j JOIN company c ON c.id = j.company_id"
                                     + " LEFT JOIN job_match m ON m.job_id = j.id";

    /**
     * Every filter except the tags as SQL, appended after {@code WHERE 1 = 1}; params are
     * added in order. Tags are matched in Java by {@link TagMatcher}.
     */
    static String where(JobFilter f, List<Object> params) {
        StringBuilder sql = new StringBuilder();

        if (f.companyId() != null) {
            sql.append(" AND j.company_id = ?");
            params.add(f.companyId());
        }
        if (f.state() != null && !f.state().isBlank()) {
            sql.append(" AND j.state = ?");
            params.add(f.state().toUpperCase());
        }
        if (f.germanyOnly()) {
            sql.append(" AND j.in_germany");
        }
        if (!f.includeFiltered()) {
            // Rule-filtered jobs stay in the database so they can be reviewed on demand,
            // but they never appear in the normal list.
            sql.append(" AND (m.state IS NULL OR m.state <> 'FILTERED')");
        }
        String freshness = f.freshness();
        if (freshness != null && !freshness.isBlank()) {
            if ("scan".equalsIgnoreCase(freshness)) {
                // Exactly the jobs the most recent finished scan created - no time arithmetic,
                // so two scans a minute apart stay distinguishable.
                sql.append(" AND j.first_seen_batch = (SELECT max(id) FROM scan_batch"
                         + " WHERE finished_at IS NOT NULL)");
            } else {
                int hours = parseHours(freshness);
                if (hours > 0) {
                    sql.append(" AND j.first_seen_at > now() - make_interval(hours => ?)");
                    params.add(hours);
                }
            }
        }
        if (f.postedWithinHours() != null && f.postedWithinHours() > 0) {
            // Only jobs whose source actually reported a date; a missing date is not "old".
            sql.append(" AND j.posted_at IS NOT NULL"
                     + " AND j.posted_at > now() - make_interval(hours => ?)");
            params.add(f.postedWithinHours());
        }
        if (!f.includeDismissed()) {
            sql.append(" AND NOT j.dismissed AND (j.snoozed_until IS NULL OR j.snoozed_until < now())");
        }
        if (f.hideApplied()) {
            sql.append(" AND j.application_status IS NULL");
        }
        if (f.minChance() != null && f.minChance() > 0) {
            sql.append(" AND m.chance_score >= ?");
            params.add(f.minChance());
        }
        if (f.minFit() != null && f.minFit() > 0) {
            sql.append(" AND m.fit_score >= ?");
            params.add(f.minFit());
        }
        if (f.maxYears() != null) {
            // 0 / NULL means the posting stated nothing, which is not a reason to hide it.
            sql.append(" AND COALESCE(m.years_required, 0) <= ?");
            params.add(f.maxYears());
        }
        List<String> tooMuchGerman = JobFilter.germanAbove(f.maxGerman());
        if (!tooMuchGerman.isEmpty()) {
            sql.append(" AND (m.german_level IS NULL OR m.german_level NOT IN (")
               .append(String.join(",", tooMuchGerman.stream().map(x -> "?").toList()))
               .append("))");
            params.addAll(tooMuchGerman);
        }
        if (f.city() != null && !f.city().isBlank()) {
            // Sources disagree on the language of a city name: "Munich" from Greenhouse,
            // "München" from the BA. Either spelling finds both.
            List<String> names = JobFilter.cityVariants(f.city());
            sql.append(" AND (").append(String.join(" OR ",
                    names.stream().map(x -> "j.location_raw ILIKE ?").toList())).append(")");
            names.forEach(n -> params.add("%" + escapeLike(n) + "%"));
        }
        if (f.remoteOnly()) {
            sql.append(" AND (j.location_raw ~* '(remote|home[ -]*office|hybrid|anywhere)'"
                     + " OR j.remote_type IS NOT NULL AND j.remote_type ~* 'remote')");
        }
        if ("company".equalsIgnoreCase(f.sourceKind()) || "board".equalsIgnoreCase(f.sourceKind())) {
            boolean board = "board".equalsIgnoreCase(f.sourceKind());
            List<String> boards = JobFilter.BOARD_SOURCES.stream().sorted().toList();
            sql.append(" AND EXISTS (SELECT 1 FROM job_source s WHERE s.job_id = j.id AND s.source_type ")
               .append(board ? "IN (" : "NOT IN (")
               .append(String.join(",", boards.stream().map(x -> "?").toList()))
               .append("))");
            params.addAll(boards);
        }
        if (f.text() != null && !f.text().isBlank()) {
            // Every typed word must appear in the title, the company or the location, so
            // "java berlin" means what it says.
            for (String w : words(f.text())) {
                sql.append(" AND (j.title ~* ? OR c.name ~* ? OR j.location_raw ~* ?)");
                String rx = wordRegex(w);
                params.add(rx);
                params.add(rx);
                params.add(rx);
            }
        }
        return sql.toString();
    }

    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        for (String w : text.trim().split("[^\\p{L}\\p{N}+#.]+")) {
            // A sentence-final "." ("Node.js.") is punctuation; a leading one is ".NET".
            String cleaned = w.replaceAll("\\.+$", "");
            if (!cleaned.isBlank()) words.add(cleaned);
        }
        return words;
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static int parseHours(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static JobView mapJob(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp posted = rs.getTimestamp("posted_at");
        String sources = rs.getString("sources");
        return new JobView(
                rs.getLong("id"), rs.getString("company"), rs.getString("title"),
                rs.getString("location_raw"), rs.getString("apply_url"),
                posted == null ? null : posted.toInstant(),
                rs.getTimestamp("first_seen_at").toInstant(),
                posted != null,
                rs.getString("state"), rs.getBoolean("reposted"), rs.getBoolean("cv_generated"),
                rs.getString("application_status"),
                (Integer) rs.getObject("fit_score"), (Integer) rs.getObject("chance_score"),
                rs.getString("reason"), (Integer) rs.getObject("years_required"),
                rs.getString("german_level"), rs.getString("match_state"),
                sources == null ? List.of() : List.of(sources.split(",")));
    }

    /**
     * One word of a tag, as a Postgres regex anchored to word boundaries.
     *
     * <p>Java's Pattern.quote emits \Q...\E, which Postgres does not understand, so the
     * metacharacters are escaped by hand. The boundary markers are applied only where the
     * word actually starts or ends in a word character: "C++" anchored on the right would
     * otherwise never match anything, since "+" is not a word character.
     */
    public static String wordRegex(String word) {
        StringBuilder out = new StringBuilder();
        boolean anchorLeft = Character.isLetterOrDigit(word.charAt(0));
        boolean anchorRight = Character.isLetterOrDigit(word.charAt(word.length() - 1));
        if (anchorLeft) out.append("\\m");
        for (char c : word.toCharArray()) {
            if ("\\.^$()[]{}|*+?".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        if (anchorRight) out.append("\\M");
        return out.toString();
    }

    /**
     * Everything one company has, with no tag filtering at all. The tag bar answers "what
     * should I look at today"; this answers "what does this employer actually have", and
     * intersecting the two made the second question impossible to ask.
     */
    public List<JobView> byCompany(long companyId, boolean germanyOnly,
                                   boolean includeFiltered, int limit) {
        StringBuilder sql = new StringBuilder(BASE_SELECT);
        sql.append(" AND j.company_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        if (germanyOnly) sql.append(" AND j.in_germany");
        if (!includeFiltered) sql.append(" AND (m.state IS NULL OR m.state <> 'FILTERED')");
        sql.append(" ORDER BY m.chance_score DESC NULLS LAST,"
                 + " COALESCE(j.posted_at, j.first_seen_at) DESC LIMIT ?");
        params.add(Math.min(limit, 1000));
        return db.sql(sql.toString()).params(params).query(JobRepository::mapJob).list();
    }

    public void setDismissed(long jobId, boolean dismissed) {
        db.sql("UPDATE job SET dismissed = ?, updated_at = now() WHERE id = ?")
          .params(dismissed, jobId).update();
    }

    public void snooze(long jobId, Instant until) {
        db.sql("UPDATE job SET snoozed_until = ?, updated_at = now() WHERE id = ?")
          .params(until == null ? null : Timestamp.from(until), jobId).update();
    }

    public void setApplicationStatus(long jobId, String status) {
        db.sql("UPDATE job SET application_status = ?, updated_at = now() WHERE id = ?")
          .params(status, jobId).update();
    }
}
