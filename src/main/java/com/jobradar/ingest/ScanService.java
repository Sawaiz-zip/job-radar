package com.jobradar.ingest;

import com.jobradar.company.AtsDetector;
import com.jobradar.company.AtsProbe;
import com.jobradar.company.DetectionVerifier;
import com.jobradar.company.Company;
import com.jobradar.company.CompanyRepository;
import com.jobradar.company.CompanyResolver;
import com.jobradar.dedupe.JobUpsertService;
import com.jobradar.source.FetchResult;
import com.jobradar.match.HardFilters;
import com.jobradar.source.BoardAdapter;
import com.jobradar.source.JobSourceAdapter;
import com.jobradar.source.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Orchestrates one scan: fetch, upsert through the dedupe service, record the run. */
@Service
public class ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanService.class);

    /** Consecutive failed detail-page fetches after which a board's details are skipped. */
    static final int DETAIL_FAILURES_BEFORE_STOP = 5;
    /** Upper bound on the time one board may spend fetching detail pages per scan. */
    static final Duration DETAIL_TIME_BUDGET = Duration.ofMinutes(6); // BA needs ~4 min for 250 healthy fetches

    private final CompanyRepository companies;
    private final List<JobSourceAdapter> adapters;
    private final JobUpsertService upsert;
    private final AtsDetector detector;
    private final AtsProbe probe;
    private final DetectionVerifier verifier;
    private final List<BoardAdapter> boards;
    private final CompanyResolver resolver;
    private final HardFilters filters;
    private final JdbcClient db;
    private final int detectPerScan;
    private final int probeRetryDays;

    public ScanService(CompanyRepository companies, List<JobSourceAdapter> adapters,
                       JobUpsertService upsert, AtsDetector detector, AtsProbe probe,
                       DetectionVerifier verifier, List<BoardAdapter> boards,
                       CompanyResolver resolver, HardFilters filters, JdbcClient db,
                       @org.springframework.beans.factory.annotation.Value(
                               "${jobradar.detect.per-scan:40}") int detectPerScan,
                       @org.springframework.beans.factory.annotation.Value(
                               "${jobradar.detect.retry-days:30}") int probeRetryDays) {
        this.companies = companies;
        this.adapters = adapters;
        this.upsert = upsert;
        this.detector = detector;
        this.probe = probe;
        this.verifier = verifier;
        this.boards = boards;
        this.resolver = resolver;
        this.filters = filters;
        this.db = db;
        this.detectPerScan = detectPerScan;
        this.probeRetryDays = probeRetryDays;
    }

    public record CompanyScan(String company, String source, int found, int created, String error) {}

    public record ScanSummary(int companiesScanned, int jobsFound, int jobsCreated,
                              int failures, List<CompanyScan> details) {}

    /**
     * Opens a scan batch so "what did this scan bring in" is an exact question later, not a
     * guess at a time window. Every job stored while the batch is open is stamped with it.
     */
    private long openBatch(String kind) {
        Long id = db.sql("INSERT INTO scan_batch (kind) VALUES (?) RETURNING id")
                    .param(kind).query(Long.class).single();
        currentBatch.set(id);
        return id;
    }

    private void closeBatch(long batchId, ScanSummary summary) {
        db.sql("""
               UPDATE scan_batch SET finished_at = now(), sources = ?, jobs_seen = ?,
                                     jobs_new = ?, failures = ?
                WHERE id = ?
               """)
          .params(summary.companiesScanned(), summary.jobsFound(), summary.jobsCreated(),
                  summary.failures(), batchId)
          .update();
        currentBatch.remove();
    }

    /** The batch the current thread is scanning under, if any. */
    private final ThreadLocal<Long> currentBatch = new ThreadLocal<>();

    public ScanSummary scanAll() {
        long batchId = openBatch("ALL");
        // Board scans keep adding employers, and an employer whose ATS is never looked for is
        // only ever seen through a board. Detection used to be a manual endpoint, which left
        // 457 of 518 companies unprobed and the job list dominated by Arbeitnow. A bounded
        // slice runs before every full scan, so anything it confirms is scanned in this run.
        if (detectPerScan > 0) {
            int found = detectAll(detectPerScan);
            log.info("ATS detection: {} new boards from up to {} companies", found, detectPerScan);
        }
        List<Company> targets = companies.findScannable();
        log.info("scanning {} companies with a detected ATS", targets.size());
        List<CompanyScan> results = new java.util.ArrayList<>(targets.stream().map(this::scanCompany).toList());
        results.addAll(scanBoards(false));
        ScanSummary summary = summarize(results);
        closeBatch(batchId, summary);
        return summary;
    }

    /**
     * The public boards: one call returns many employers, so each job's company has to be
     * resolved, and the title rules run before anything expensive happens.
     */
    public List<CompanyScan> scanBoards() {
        return scanBoards(true);
    }

    public List<CompanyScan> scanBoards(boolean ownBatch) {
        long batchId = ownBatch ? openBatch("BOARDS") : -1;
        try {
            return scanBoardsInner();
        } finally {
            if (ownBatch) closeBatch(batchId, summarize(List.of()));
        }
    }

    private List<CompanyScan> scanBoardsInner() {
        List<String> queries = db.sql(
                "SELECT text FROM search_term WHERE active AND kind = 'INCLUDE' ORDER BY text")
                .query(String.class).list();
        if (queries.isEmpty()) return List.of();

        List<CompanyScan> results = new java.util.ArrayList<>();
        for (BoardAdapter board : boards) {
            Instant startedAt = Instant.now();
            var result = board.search(queries);
            if (result.isError()) {
                log.warn("{} search failed: {}", board.type(), result.error());
                recordBoardRun(board, startedAt, result.error(), 0, 0);
                results.add(new CompanyScan(board.type().name(), board.type().name(), 0, 0, result.error()));
                continue;
            }

            int created = 0, kept = 0, detailFetches = 0, failedInARow = 0;
            String detailsStopped = null;
            for (var raw : result.jobs()) {
                // Cheap title gate first: most board results are not engineering roles at all.
                if (!filters.evaluate(raw.title(), raw.descriptionText()).keep()) continue;

                var job = raw;
                if (detailsStopped == null && job.descriptionText() == null
                        && detailFetches < board.maxDetailFetches()) {
                    String description = board.fetchDescription(job);
                    detailFetches++;
                    failedInARow = description == null ? failedInARow + 1 : 0;
                    // A board that starts stalling detail pages (bot protection answers by
                    // holding the connection until the read timeout) would otherwise cost
                    // 20 s per job: 120 fetches turned a 90 s step into 40 minutes. Stop
                    // asking, keep the jobs without a description, and say so in the run.
                    if (failedInARow >= DETAIL_FAILURES_BEFORE_STOP) {
                        detailsStopped = failedInARow + " detail pages in a row failed";
                    } else if (Duration.between(startedAt, Instant.now()).compareTo(DETAIL_TIME_BUDGET) > 0) {
                        detailsStopped = "detail fetches passed the " + DETAIL_TIME_BUDGET.toMinutes() + "-minute budget";
                    }
                    if (detailsStopped != null) {
                        log.warn("{}: {}; keeping the remaining jobs without descriptions", board.type(), detailsStopped);
                    }
                    if (description != null) {
                        job = new com.jobradar.source.RawJob(job.sourceType(), job.tenant(),
                                job.externalId(), job.title(), job.location(), description,
                                job.url(), job.companyName(), job.postedAt(), job.rawPayload());
                        // Now that the text is known, the language and experience rules apply.
                        if (!filters.evaluate(job.title(), description).keep()) continue;
                    }
                }

                try {
                    long companyId = resolver.resolveOrCreate(job.companyName(), "BOARD");
                    kept++;
                    var stored = upsert.upsert(companyId, job);
                    if (stored.isNew()) {
                        created++;
                        stampBatch(stored.jobId());
                    }
                } catch (RuntimeException e) {
                    log.warn("could not store board job '{}': {}", job.title(), e.toString());
                }
            }
            log.info("{}: {} results, {} kept, {} new", board.type(), result.jobs().size(), kept, created);
            recordBoardRun(board, startedAt,
                    detailsStopped == null ? null : "partial: " + detailsStopped, kept, created);
            results.add(new CompanyScan(board.type().name(), board.type().name(), kept, created, null));
        }
        pruneOrphanEmployers();
        return results;
    }

    /**
     * Drop board-discovered employers that no longer have a job.
     *
     * <p>A board scan creates a company per employer it mentions, which is right - a job
     * needs an employer - but when those jobs go away the company should go with them.
     * Left alone it put 376 empty rows in the registry, burying the companies actually
     * being watched. Only BOARD-origin rows with no board of their own and no application
     * against them are touched; nothing the user added can be removed this way.
     */
    private void pruneOrphanEmployers() {
        int removed = db.sql("""
                DELETE FROM company c
                 WHERE c.origin = 'BOARD' AND c.ats_tenant IS NULL
                   AND NOT EXISTS (SELECT 1 FROM job j WHERE j.company_id = c.id)
                   AND NOT EXISTS (SELECT 1 FROM application a WHERE a.company_id = c.id)
                """).update();
        if (removed > 0) log.info("pruned {} board employers with no jobs left", removed);
    }

    private void recordBoardRun(BoardAdapter board, Instant startedAt, String error, int count, int created) {
        db.sql("""
               INSERT INTO source_run (company_id, source_type, started_at, finished_at,
                                       job_count, new_count, error, duration_ms)
               VALUES (NULL,?,?,now(),?,?,?,?)
               """)
          .params(board.type().name(), java.sql.Timestamp.from(startedAt), count, created, error,
                  java.time.Duration.between(startedAt, Instant.now()).toMillis())
          .update();
    }

    public ScanSummary scanOne(long companyId) {
        Company c = companies.findById(companyId)
                .orElseThrow(() -> new IllegalArgumentException("no company " + companyId));
        long batchId = openBatch("COMPANY");
        ScanSummary summary = summarize(List.of(scanCompany(c)));
        closeBatch(batchId, summary);
        return summary;
    }

    private ScanSummary summarize(List<CompanyScan> results) {
        return new ScanSummary(
                results.size(),
                results.stream().mapToInt(CompanyScan::found).sum(),
                results.stream().mapToInt(CompanyScan::created).sum(),
                (int) results.stream().filter(r -> r.error() != null).count(),
                results);
    }

    public CompanyScan scanCompany(Company company) {
        Optional<JobSourceAdapter> adapter = adapters.stream().filter(a -> a.supports(company)).findFirst();
        if (adapter.isEmpty()) {
            // A confirmed board nothing can read is a broken source, not an empty one, so it
            // is recorded like any other failure instead of vanishing from the health view.
            String why = "no adapter can read this board (" + company.atsType()
                    + (company.atsType() == com.jobradar.source.SourceType.WORKDAY && company.atsSite() == null
                       ? ", Workday site name missing" : "") + ")";
            companies.recordRunOutcome(company.id(), false, 0);
            db.sql("""
                   INSERT INTO source_run (company_id, source_type, started_at, finished_at,
                                           job_count, new_count, error, duration_ms)
                   VALUES (?,?,now(),now(),0,0,?,0)
                   """).params(company.id(), company.atsType().name(), why).update();
            return new CompanyScan(company.name(), company.atsType().name(), 0, 0, why);
        }
        Instant startedAt = Instant.now();
        FetchResult result = adapter.get().fetch(company);

        if (result.isError()) {
            companies.recordRunOutcome(company.id(), false, 0);
            recordRun(company, adapter.get(), startedAt, result, 0);
            log.warn("scan failed for {}: {}", company.name(), result.error());
            return new CompanyScan(company.name(), adapter.get().type().name(), 0, 0, result.error());
        }

        int created = 0;
        for (RawJob raw : result.jobs()) {
            try {
                var stored = upsert.upsert(company.id(), raw);
                if (stored.isNew()) {
                    created++;
                    stampBatch(stored.jobId());
                }
            } catch (RuntimeException e) {
                log.warn("could not store '{}' from {}: {}", raw.title(), company.name(), e.toString());
            }
        }
        upsert.closeMissing(company.id(), startedAt);
        companies.recordRunOutcome(company.id(), true, result.jobs().size());
        recordRun(company, adapter.get(), startedAt, result, created);

        log.info("{}: {} jobs ({} new)", company.name(), result.jobs().size(), created);
        return new CompanyScan(company.name(), adapter.get().type().name(),
                result.jobs().size(), created, null);
    }

    /** Records which scan first saw a job, so "new in this scan" needs no time arithmetic. */
    private void stampBatch(long jobId) {
        Long batchId = currentBatch.get();
        if (batchId == null) return;
        db.sql("UPDATE job SET first_seen_batch = ? WHERE id = ? AND first_seen_batch IS NULL")
          .params(batchId, jobId).update();
    }

    private void recordRun(Company c, JobSourceAdapter adapter, Instant startedAt,
                           FetchResult result, int created) {
        db.sql("""
               INSERT INTO source_run (company_id, source_type, started_at, finished_at,
                                       http_status, job_count, new_count, error, duration_ms)
               VALUES (?,?,?,now(),?,?,?,?,?)
               """)
          .params(c.id(), adapter.type().name(), Timestamp.from(startedAt), result.httpStatus(),
                  result.jobs().size(), created, result.error(),
                  java.time.Duration.between(startedAt, Instant.now()).toMillis())
          .update();
    }

    /**
     * Runs ATS detection for companies with no tenant yet, those never probed first and,
     * among them, the ones with the most open jobs - the employers already proven active.
     * A miss is stamped too, so the next run moves on to other companies instead of asking
     * the same dozen again; misses are retried after {@code probeRetryDays}.
     */
    public int detectAll(int limit) {
        int detected = 0;
        for (Company c : companies.findDetectionCandidates(limit, probeRetryDays)) {
            companies.markProbed(c.id());

            // Ask the ATS APIs first - far higher hit rate than parsing a careers page,
            // most of which render their board link in JavaScript.
            var found = probe.probe(c);
            if (found.isEmpty() && c.careersUrl() != null) {
                AtsDetector.Detection page = detector.detect(c);
                if (page.found()) found = java.util.Optional.of(page);
            }
            if (found.isPresent()) {
                AtsDetector.Detection d = found.get();
                companies.saveDetection(c.id(), d.type(), d.tenant(), d.site(), d.region(),
                        d.checkedUrl(), "GUESSED",
                        "found by " + (d.error() == null ? "probe" : d.error()));
                var verdict = verifier.verify(companies.findById(c.id()).orElseThrow());
                if ("REJECTED".equals(verdict.confidence())) {
                    companies.clearDetection(c.id(), verdict.evidence());
                } else {
                    companies.saveConfidence(c.id(), verdict.confidence(), verdict.evidence());
                    detected++;
                }
            }
        }
        return detected;
    }

    /** Re-checks every tenant that has not been confirmed yet. */
    public Map<String, Integer> verifyDetections() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Company c : companies.findByConfidence("GUESSED")) {
            DetectionVerifier.Verdict v = verifier.verify(c);
            if ("REJECTED".equals(v.confidence())) {
                log.info("rejected {} -> {}:{} ({})", c.name(), c.atsType(), c.atsTenant(), v.evidence());
                companies.clearDetection(c.id(), v.evidence());
            } else {
                companies.saveConfidence(c.id(), v.confidence(), v.evidence());
            }
            counts.merge(v.confidence(), 1, Integer::sum);
        }
        return counts;
    }
}
