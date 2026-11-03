package com.jobradar.api;

import com.jobradar.company.CompanyRepository;
import com.jobradar.ingest.AddCompanyService;
import com.jobradar.ingest.ScanService;
import com.jobradar.application.ApplicationService;
import com.jobradar.mail.ApplicationMailSync;
import com.jobradar.mail.GmailClient;
import com.jobradar.match.MatchService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = {"http://localhost:4200"})
public class ApiController {

    private final ScanService scans;
    private final JobRepository jobs;
    private final CompanyRepository companies;
    private final MatchService matches;
    private final AddCompanyService adder;
    private final ApplicationService applications;
    private final GmailClient gmail;
    private final ApplicationMailSync mailSync;
    private final JdbcClient db;

    public ApiController(ScanService scans, JobRepository jobs, CompanyRepository companies,
                         MatchService matches, AddCompanyService adder,
                         ApplicationService applications, GmailClient gmail,
                         ApplicationMailSync mailSync, JdbcClient db) {
        this.scans = scans;
        this.jobs = jobs;
        this.companies = companies;
        this.matches = matches;
        this.adder = adder;
        this.applications = applications;
        this.gmail = gmail;
        this.mailSync = mailSync;
        this.db = db;
    }

    /** One click: pull everything, then score whatever is new. */
    @PostMapping("/scan")
    public Map<String, Object> scan(@RequestParam(required = false) Long companyId,
                                    @RequestParam(defaultValue = "true") boolean score) {
        ScanService.ScanSummary summary = companyId == null ? scans.scanAll() : scans.scanOne(companyId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scan", summary);
        if (score) out.put("scoring", matches.scoreNewJobs(600));
        return out;
    }

    /** Search the public boards (Arbeitnow, Bundesagentur) without touching company ATSs. */
    @PostMapping("/scan-boards")
    public Map<String, Object> scanBoards(@RequestParam(defaultValue = "true") boolean score) {
        var results = scans.scanBoards();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("boards", results);
        if (score) out.put("scoring", matches.scoreNewJobs(600));
        return out;
    }

    @PostMapping("/detect")
    public Map<String, Object> detect(@RequestParam(defaultValue = "25") int limit) {
        return Map.of("detected", scans.detectAll(limit));
    }

    /** Called by the CV tool when a CV is generated for a company not on the watchlist. */
    @PostMapping("/companies")
    public Map<String, Object> addCompany(@RequestBody Map<String, String> body) {
        return adder.add(body.get("name"), body.get("website"), body.get("jobUrl"), true);
    }

    /** Lets the CV tool ask whether it needs to offer the "add company" button at all. */
    @GetMapping("/companies/known")
    public Map<String, Object> isKnown(@RequestParam String name) {
        var found = companies.findByNormalizedName(new com.jobradar.dedupe.Normalizer().normalizeCompany(name));
        return Map.of("known", found.isPresent(),
                      "scanned", found.map(c -> c.atsTenant() != null).orElse(false),
                      "name", found.map(com.jobradar.company.Company::name).orElse(name));
    }

    @PostMapping("/verify-detections")
    public Map<String, Integer> verifyDetections() {
        return scans.verifyDetections();
    }

    /** Boards found by slug guess that nothing has confirmed yet - one click each. */
    @GetMapping("/companies/unconfirmed")
    public List<Map<String, Object>> unconfirmed() {
        return db.sql("""
                SELECT id, name, ats_type, ats_tenant, detection_evidence, careers_url
                  FROM company WHERE detection_confidence = 'GUESSED' ORDER BY priority, name
                """).query().listOfRows();
    }

    @PostMapping("/companies/{id}/confirm")
    public Map<String, Object> confirm(@PathVariable long id) {
        companies.saveConfidence(id, "CONFIRMED", "confirmed by user");
        return Map.of("ok", true);
    }

    @PostMapping("/companies/{id}/reject")
    public Map<String, Object> reject(@PathVariable long id) {
        companies.clearDetection(id, "rejected by user");
        return Map.of("ok", true);
    }

    @PostMapping("/score")
    public MatchService.ScoreRun score(@RequestParam(defaultValue = "400") int limit) {
        return matches.scoreNewJobs(limit);
    }

    /**
     * The job list. The body stays a plain array; how many jobs matched in all travels in
     * {@code X-Total-Count}, so a capped page can say "300 of 1,240" instead of looking
     * like everything.
     */
    @GetMapping("/jobs")
    public ResponseEntity<List<JobView>> jobs(@RequestParam(defaultValue = "OPEN") String state,
                              @RequestParam(defaultValue = "date") String sort,
                              @RequestParam(required = false) List<String> term,
                              @RequestParam(required = false) String text,
                              @RequestParam(required = false) String city,
                              @RequestParam(defaultValue = "false") boolean remoteOnly,
                              @RequestParam(required = false) String sourceKind,
                              @RequestParam(required = false) Integer minChance,
                              @RequestParam(required = false) Integer minFit,
                              @RequestParam(required = false) Integer maxYears,
                              @RequestParam(required = false) String maxGerman,
                              @RequestParam(defaultValue = "false") boolean hideApplied,
                              @RequestParam(defaultValue = "false") boolean includeDismissed,
                              @RequestParam(defaultValue = "true") boolean germanyOnly,
                              @RequestParam(defaultValue = "false") boolean includeFiltered,
                              @RequestParam(required = false) String freshness,
                              @RequestParam(required = false) Integer postedWithinHours,
                              @RequestParam(required = false) Long companyId,
                              @RequestParam(defaultValue = "200") int limit) {
        var page = jobs.search(new JobFilter(state, sort, term, text, city, remoteOnly, sourceKind,
                minChance, minFit, maxYears, maxGerman, hideApplied, includeDismissed,
                germanyOnly, includeFiltered, freshness, postedWithinHours, companyId, limit));
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(page.total()))
                .header("Access-Control-Expose-Headers", "X-Total-Count")
                .body(page.jobs());
    }

    /**
     * The posting text as the source gave it — clean, because it came from an API or a
     * board feed rather than from scraping a page. Served on demand rather than with the
     * list: three hundred descriptions is a megabyte nobody asked for.
     */
    @GetMapping("/jobs/{id}/description")
    public Map<String, Object> jobDescription(@PathVariable long id) {
        var rows = db.sql("""
                SELECT c.name AS company, j.title, j.location_raw, j.apply_url, j.description_text,
                       (SELECT string_agg(DISTINCT s.source_type, ',') FROM job_source s
                         WHERE s.job_id = j.id) AS sources
                  FROM job j JOIN company c ON c.id = j.company_id
                 WHERE j.id = ?
                """).param(id).query().listOfRows();
        if (rows.isEmpty()) return Map.of("ok", false, "message", "no such job");
        Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
        row.put("ok", true);
        return row;
    }

    @PatchMapping("/jobs/{id}")
    public Map<String, Object> updateJob(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.containsKey("dismissed")) {
            jobs.setDismissed(id, Boolean.TRUE.equals(body.get("dismissed")));
        }
        if (body.containsKey("applicationStatus")) {
            jobs.setApplicationStatus(id, (String) body.get("applicationStatus"));
        }
        if (body.containsKey("snoozeDays")) {
            int days = ((Number) body.get("snoozeDays")).intValue();
            jobs.snooze(id, days <= 0 ? null : Instant.now().plus(days, ChronoUnit.DAYS));
        }
        return Map.of("ok", true);
    }

    /**
     * @param scope "watchlist" (default) for companies deliberately being watched, "board"
     *              for employers a board mentioned, "all" for both. Without the split the
     *              tab was 513 one-hit board employers burying the 128 that matter.
     */
    @GetMapping("/companies")
    public List<Map<String, Object>> companies(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "watchlist") String scope) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.name, c.category, c.priority, c.ats_type, c.ats_tenant, c.status,
                       c.origin, c.detection_confidence, c.last_success_at, c.last_job_count,
                       c.consecutive_errors, c.careers_url,
                       (SELECT count(*) FROM job j WHERE j.company_id = c.id AND j.state = 'OPEN') AS open_jobs,
                       (SELECT count(*) FROM job j WHERE j.company_id = c.id
                          AND j.state = 'OPEN' AND j.in_germany) AS open_jobs_de
                  FROM company c
                 WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (!"all".equalsIgnoreCase(scope)) {
            sql.append("board".equalsIgnoreCase(scope)
                    ? " AND c.origin = 'BOARD'"
                    : " AND c.origin <> 'BOARD'");
        }
        if (status != null) {
            sql.append(" AND c.status = ?");
            params.add(status);
        }
        sql.append(" ORDER BY open_jobs_de DESC, open_jobs DESC, c.priority, c.name");

        List<Map<String, Object>> rows = db.sql(sql.toString()).params(params).query().listOfRows();
        // Say WHY a company shows nothing. "No board found", "waiting for confirmation" and
        // "scanned, nothing open" are three different situations that all render as 0.
        for (Map<String, Object> row : rows) {
            row.put("state", describeCompanyState(row));
        }
        return rows;
    }

    private static String describeCompanyState(Map<String, Object> row) {
        long open = ((Number) row.getOrDefault("open_jobs", 0L)).longValue();
        String confidence = String.valueOf(row.get("detection_confidence"));
        Object tenant = row.get("ats_tenant");

        if (tenant == null) {
            return "REJECTED".equals(confidence)
                    ? "no usable board (a guess was checked and rejected)"
                    : "no job board found - this employer cannot be scanned";
        }
        if (!"CONFIRMED".equals(confidence)) return "board found, waiting for your confirmation";
        if (row.get("last_success_at") == null) return "confirmed, not scanned yet";
        return open == 0 ? "scanned, nothing open right now" : "active";
    }

    /** Every job from one company, ignoring the tag bar. */
    @GetMapping("/companies/{id}/jobs")
    public List<JobView> companyJobs(@PathVariable long id,
                                     @RequestParam(defaultValue = "false") boolean germanyOnly,
                                     @RequestParam(defaultValue = "true") boolean includeFiltered,
                                     @RequestParam(defaultValue = "500") int limit) {
        return jobs.byCompany(id, germanyOnly, includeFiltered, limit);
    }

    @GetMapping("/search-terms")
    public List<Map<String, Object>> terms() {
        return db.sql("SELECT id, text, term_group, kind, active FROM search_term ORDER BY term_group, text")
                 .query().listOfRows();
    }

    @PostMapping("/search-terms")
    public Map<String, Object> addTerm(@RequestBody Map<String, String> body) {
        db.sql("""
               INSERT INTO search_term (text, term_group, kind)
               VALUES (?,?,?) ON CONFLICT (text, kind) DO NOTHING
               """)
          .params(body.get("text"),
                  body.getOrDefault("group", "Custom"),
                  body.getOrDefault("kind", "INCLUDE"))
          .update();
        return Map.of("ok", true);
    }

    @PatchMapping("/search-terms/{id}")
    public Map<String, Object> updateTerm(@PathVariable long id, @RequestBody Map<String, Object> body) {
        if (body.containsKey("active")) {
            db.sql("UPDATE search_term SET active = ? WHERE id = ?")
              .params(Boolean.TRUE.equals(body.get("active")), id).update();
        }
        if (body.containsKey("text")) {
            db.sql("UPDATE search_term SET text = ? WHERE id = ?")
              .params(body.get("text"), id).update();
        }
        return Map.of("ok", true);
    }

    /** Switch many tags at once (a whole group), in one statement rather than N requests. */
    @PostMapping("/search-terms/active")
    public Map<String, Object> setTermsActive(@RequestBody Map<String, Object> body) {
        List<Long> ids = ((List<?>) body.getOrDefault("ids", List.of())).stream()
                .map(x -> ((Number) x).longValue()).toList();
        boolean active = Boolean.TRUE.equals(body.get("active"));
        if (ids.isEmpty()) return Map.of("ok", true, "updated", 0);
        int n = db.sql("UPDATE search_term SET active = :active WHERE id IN (:ids)")
                  .param("active", active).param("ids", ids).update();
        return Map.of("ok", true, "updated", n);
    }

    @DeleteMapping("/search-terms/{id}")
    public Map<String, Object> deleteTerm(@PathVariable long id) {
        db.sql("DELETE FROM search_term WHERE id = ?").param(id).update();
        return Map.of("ok", true);
    }

    // ── application status ────────────────────────────────────────────────

    /** Called by the CV tool when it finishes a CV. */
    @PostMapping("/jobs/mark-applied")
    public Map<String, Object> markApplied(@RequestBody Map<String, String> body) {
        return applications.record(body.get("company"), body.get("url"),
                                   body.get("title"), body.get("folder"));
    }

    /** Rebuilds application history from the CV folders on disk. Safe to re-run. */
    @PostMapping("/applications/backfill")
    public ApplicationService.BackfillResult backfill() {
        return applications.backfillFromCvFolders();
    }

    @GetMapping("/applications")
    public List<Map<String, Object>> applications() {
        return applications.list();
    }

    // ── gmail ─────────────────────────────────────────────────────────────

    /** What each scan brought in, newest first — the source of the "new this scan" filter. */
    @GetMapping("/scans")
    public List<Map<String, Object>> scans(@RequestParam(defaultValue = "10") int limit) {
        return db.sql("""
                SELECT b.id, b.kind, b.started_at, b.finished_at, b.sources, b.jobs_seen,
                       b.jobs_new, b.failures,
                       (SELECT count(*) FROM job j
                         WHERE j.first_seen_batch = b.id AND j.in_germany) AS new_in_germany
                  FROM scan_batch b
                 WHERE b.finished_at IS NOT NULL
                 ORDER BY b.started_at DESC LIMIT ?
                """).param(Math.min(limit, 50)).query().listOfRows();
    }

    @GetMapping("/gmail/status")
    public Map<String, Object> gmailStatus() {
        return mailSync.status();
    }

    @GetMapping("/gmail/auth-url")
    public Map<String, Object> gmailAuthUrl() {
        if (!gmail.hasCredentials()) {
            return Map.of("ok", false, "message",
                    "No Google OAuth client configured. See README: Connecting Gmail.");
        }
        return Map.of("ok", true, "url", gmail.authUrl());
    }

    /** Google redirects the browser here after the user grants access. */
    @GetMapping("/gmail/callback")
    public String gmailCallback(@RequestParam(required = false) String code,
                                @RequestParam(required = false) String error) {
        if (error != null) return page("Gmail was not connected", "Google reported: " + error);
        if (code == null) return page("Gmail was not connected", "No authorisation code was returned.");
        try {
            gmail.completeAuth(code);
            return page("Gmail connected", "You can close this tab and go back to Job Radar.");
        } catch (RuntimeException e) {
            return page("Gmail was not connected", e.getMessage());
        }
    }

    @PostMapping("/gmail/disconnect")
    public Map<String, Object> gmailDisconnect() {
        gmail.disconnect();
        return Map.of("ok", true);
    }

    @PostMapping("/gmail/sync")
    public ApplicationMailSync.SyncResult gmailSync(@RequestParam(defaultValue = "120") int lookbackDays) {
        return mailSync.sync(lookbackDays);
    }

    private static String page(String title, String message) {
        return """
               <!doctype html><meta charset="utf-8">
               <title>%s</title>
               <body style="font:15px system-ui;background:#0f1115;color:#e6e9ef;padding:60px;text-align:center">
                 <h2 style="margin:0 0 10px">%s</h2><p style="color:#8b95a5">%s</p>
               </body>
               """.formatted(title, title, message);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("companies", one("SELECT count(*) FROM company"));
        out.put("withAts", one("SELECT count(*) FROM company WHERE ats_tenant IS NOT NULL"));
        out.put("confirmed", one("SELECT count(*) FROM company WHERE detection_confidence = 'CONFIRMED'"));
        out.put("unconfirmed", one("SELECT count(*) FROM company WHERE detection_confidence = 'GUESSED'"));
        out.put("openJobs", one("SELECT count(*) FROM job WHERE state = 'OPEN' AND in_germany"));
        out.put("openJobsWorldwide", one("SELECT count(*) FROM job WHERE state = 'OPEN'"));
        out.put("applications", one("SELECT count(*) FROM application"));
        out.put("scoring", matches.stats());
        out.put("gmail", mailSync.status());
        out.put("runtime", matches.modelInfo());
        out.put("byStatus", db.sql("SELECT status, count(*) AS n FROM company GROUP BY status").query().listOfRows());
        out.put("lastRuns", db.sql("""
                SELECT COALESCE(c.name, r.source_type) AS name, r.source_type, r.started_at,
                       r.job_count, r.new_count, r.error
                  FROM source_run r LEFT JOIN company c ON c.id = r.company_id
                 ORDER BY r.started_at DESC LIMIT 20
                """).query().listOfRows());
        return out;
    }

    /**
     * One answer to "which source is not working": a verdict per source type from its latest
     * runs, every company board that is failing or parked with its last error, and how much
     * of the registry is actually scanned. A source that returned zero jobs on its last run
     * is flagged EMPTY rather than OK - a silent zero is the breakage that hides best.
     */
    @GetMapping("/sources")
    public Map<String, Object> sources() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bySource", db.sql("""
                WITH latest AS (
                    SELECT DISTINCT ON (r.source_type, COALESCE(r.company_id, 0)) r.*
                      FROM source_run r LEFT JOIN company c ON c.id = r.company_id
                     WHERE r.company_id IS NULL OR c.enabled
                     ORDER BY r.source_type, COALESCE(r.company_id, 0), r.started_at DESC)
                SELECT l.source_type,
                       count(*)                                     AS targets,
                       count(*) FILTER (WHERE l.error IS NULL)      AS ok,
                       count(*) FILTER (WHERE l.error IS NOT NULL)  AS failing,
                       COALESCE(sum(l.job_count), 0)                AS jobs_last_run,
                       COALESCE(sum(l.new_count), 0)                AS new_last_run,
                       max(l.started_at)                            AS last_run,
                       (SELECT count(DISTINCT s.job_id) FROM job_source s JOIN job j ON j.id = s.job_id
                          LEFT JOIN job_match m ON m.job_id = j.id
                         WHERE s.source_type = l.source_type AND j.state = 'OPEN' AND j.in_germany
                           AND NOT j.dismissed AND (m.state IS NULL OR m.state <> 'FILTERED')) AS visible_jobs,
                       CASE WHEN count(*) FILTER (WHERE l.error IS NULL) = 0 THEN 'FAILING'
                            WHEN count(*) FILTER (WHERE l.error IS NOT NULL) > 0 THEN 'PARTIAL'
                            WHEN COALESCE(sum(l.job_count), 0) = 0 THEN 'EMPTY'
                            ELSE 'OK' END                           AS verdict,
                       (array_agg(l.error ORDER BY l.started_at DESC) FILTER (WHERE l.error IS NOT NULL))[1]
                                                                    AS last_error
                  FROM latest l
                 GROUP BY l.source_type
                 ORDER BY l.source_type
                """).query().listOfRows());
        out.put("problems", db.sql("""
                SELECT c.id, c.name, c.ats_type, c.ats_tenant, c.status, c.enabled,
                       c.consecutive_errors, c.last_success_at, c.notes,
                       (SELECT r.error FROM source_run r WHERE r.company_id = c.id
                         ORDER BY r.started_at DESC LIMIT 1) AS last_error
                  FROM company c
                 WHERE c.ats_tenant IS NOT NULL AND (c.status <> 'OK' OR NOT c.enabled)
                 ORDER BY c.enabled DESC, c.consecutive_errors DESC, c.name
                """).query().listOfRows());
        out.put("coverage", db.sql("""
                SELECT count(*)                                                                AS companies,
                       count(*) FILTER (WHERE detection_confidence = 'CONFIRMED' AND enabled)  AS scanned_directly,
                       count(*) FILTER (WHERE detection_confidence = 'GUESSED')                 AS awaiting_confirmation,
                       count(*) FILTER (WHERE ats_tenant IS NULL AND probed_at IS NULL AND enabled) AS never_probed,
                       count(*) FILTER (WHERE ats_tenant IS NULL AND probed_at IS NOT NULL)     AS probed_no_ats
                  FROM company
                """).query().singleRow());
        return out;
    }

    private Long one(String sql) {
        return db.sql(sql).query(Long.class).single();
    }
}
