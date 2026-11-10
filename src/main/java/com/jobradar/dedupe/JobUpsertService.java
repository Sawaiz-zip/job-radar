package com.jobradar.dedupe;

import com.jobradar.config.HttpClients.RadarProperties;
import com.jobradar.source.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * The single write path for jobs (constitution #1). Every source - scans, the CV
 * tool, email alerts - goes through here, and running it twice changes nothing
 * the second time.
 *
 * <p>Layers are tried cheapest first. L1-L3 are exact and merge automatically;
 * anything fuzzier is deliberately left to a review queue rather than merged,
 * because a wrong auto-merge silently hides a real job.
 */
@Service
public class JobUpsertService {

    private static final Logger log = LoggerFactory.getLogger(JobUpsertService.class);

    private final JdbcClient db;
    private final UrlCanonicalizer canonicalizer;
    private final Normalizer normalizer;
    private final RadarProperties props;

    public JobUpsertService(JdbcClient db, UrlCanonicalizer canonicalizer,
                            Normalizer normalizer, RadarProperties props) {
        this.db = db;
        this.canonicalizer = canonicalizer;
        this.normalizer = normalizer;
        this.props = props;
    }

    public record Result(long jobId, UpsertOutcome outcome) {
        public boolean isNew() {
            return outcome == UpsertOutcome.CREATED;
        }
    }

    @Transactional
    /**
     * Whether this posting is already stored with its text. A board scan uses it to skip
     * the detail request for jobs it already has: an existing job is only touched on
     * upsert, so fetching its description again costs a request and changes nothing.
     */
    public boolean hasDescription(RawJob raw) {
        if (raw.externalId() == null) return false;
        return db.sql("""
                SELECT EXISTS (SELECT 1 FROM job_source s JOIN job j ON j.id = s.job_id
                                WHERE s.source_type = ? AND s.tenant IS NOT DISTINCT FROM ?
                                  AND s.external_id = ? AND j.description_text IS NOT NULL)
                """)
                .params(raw.sourceType().name(), raw.tenant(), raw.externalId())
                .query(Boolean.class).single();
    }

    public Result upsert(long companyId, RawJob raw) {
        if (raw.url() == null || raw.title() == null) {
            throw new IllegalArgumentException("a job needs at least a url and a title");
        }
        ParsedJobUrl url = canonicalizer.parse(raw.url());

        // L1: exact same link, seen before.
        Optional<Long> byUrl = jobIdBy("canonical_url = ?", url.canonicalUrl());
        if (byUrl.isPresent()) {
            touch(byUrl.get(), url, raw);
            return new Result(byUrl.get(), UpsertOutcome.MATCHED_CANONICAL_URL);
        }

        // L2: same posting id inside the same ATS tenant, different URL shape.
        if (raw.externalId() != null) {
            Optional<Long> bySourceId = db.sql("""
                    SELECT job_id FROM job_source
                     WHERE source_type = ? AND tenant IS NOT DISTINCT FROM ? AND external_id = ?
                     LIMIT 1
                    """)
                    .params(raw.sourceType().name(), raw.tenant(), raw.externalId())
                    .query(Long.class).optional();
            if (bySourceId.isPresent()) {
                attachSource(bySourceId.get(), url, raw);
                return new Result(bySourceId.get(), UpsertOutcome.MATCHED_SOURCE_ID);
            }
        }

        // L3: same company + title + city, reported by a different source.
        String fingerprint = normalizer.fingerprint(companyId, raw.title(), raw.location());
        Optional<Long> byFingerprint = db.sql(
                "SELECT id FROM job WHERE company_id = ? AND fingerprint = ? AND state = 'OPEN' LIMIT 1")
                .params(companyId, fingerprint).query(Long.class).optional();
        if (byFingerprint.isPresent()) {
            attachSource(byFingerprint.get(), url, raw);
            return new Result(byFingerprint.get(), UpsertOutcome.MATCHED_FINGERPRINT);
        }

        // A closed job coming back inside the window is the same job, reposted.
        Optional<Long> closed = db.sql("""
                SELECT id FROM job
                 WHERE company_id = ? AND fingerprint = ? AND state = 'CLOSED'
                   AND closed_at > now() - make_interval(days => ?)
                 ORDER BY closed_at DESC LIMIT 1
                """)
                .params(companyId, fingerprint, props.repostWindowDays())
                .query(Long.class).optional();
        if (closed.isPresent()) {
            db.sql("""
                   UPDATE job SET state = 'OPEN', closed_at = NULL, reposted = TRUE,
                                  last_seen_at = now(), updated_at = now()
                    WHERE id = ?
                   """).param(closed.get()).update();
            attachSource(closed.get(), url, raw);
            return new Result(closed.get(), UpsertOutcome.REOPENED);
        }

        long jobId = insertJob(companyId, raw, fingerprint);
        attachSource(jobId, url, raw);
        return new Result(jobId, UpsertOutcome.CREATED);
    }

    private long insertJob(long companyId, RawJob raw, String fingerprint) {
        String description = raw.descriptionText();
        return db.sql("""
                INSERT INTO job (company_id, title, title_normalized, fingerprint, city_normalized,
                                 location_raw, description_text, description_hash, apply_url, posted_at,
                                 in_germany)
                VALUES (?,?,?,?,?,?,?,?,?,?, job_is_in_germany(?) OR ?)
                RETURNING id
                """)
                .params(companyId,
                        normalizer.cleanTitle(raw.title()),
                        normalizer.normalizeTitle(raw.title()),
                        fingerprint,
                        normalizer.normalizeCity(raw.location()),
                        raw.location(),
                        description,
                        description == null ? null : normalizer.sha256(description),
                        raw.url(),
                        raw.postedAt() == null ? null : Timestamp.from(raw.postedAt()),
                        raw.location(),
                        GERMANY_ONLY_SOURCES.contains(raw.sourceType()))
                .query(Long.class).single();
    }

    /**
     * Sources that only ever return German postings, so the source itself is the evidence.
     * job_is_in_germany() knows about sixty cities; BA and StepStone list Bochum, Neuss and
     * Bad Hersfeld, and a third of their jobs were hidden from the default view for it.
     * SmartRecruiters is listed because its adapter asks the API for country=de only.
     */
    private static final java.util.Set<com.jobradar.source.SourceType> GERMANY_ONLY_SOURCES =
            java.util.EnumSet.of(com.jobradar.source.SourceType.BA,
                                 com.jobradar.source.SourceType.STEPSTONE,
                                 com.jobradar.source.SourceType.SMARTRECRUITERS);

    /** A job can have many sources; this adds one without ever duplicating the job. */
    private void attachSource(long jobId, ParsedJobUrl url, RawJob raw) {
        db.sql("""
               INSERT INTO job_source (job_id, source_type, tenant, external_id,
                                       original_url, canonical_url)
               VALUES (?,?,?,?,?,?)
               ON CONFLICT (canonical_url) DO UPDATE
                  SET last_seen_at = now(), missing_count = 0
               """)
          .params(jobId, raw.sourceType().name(), raw.tenant(), raw.externalId(),
                  raw.url(), url.canonicalUrl())
          .update();
        db.sql("UPDATE job SET last_seen_at = now(), updated_at = now() WHERE id = ?")
          .param(jobId).update();
        // A job first stored without its text (a board scan ran out of detail budget) gets
        // it the next time it is seen with one. Existing text is never overwritten here.
        String description = raw.descriptionText();
        if (description != null && !description.isBlank()) {
            db.sql("""
                   UPDATE job SET description_text = ?, description_hash = ?
                    WHERE id = ? AND description_text IS NULL
                   """)
              .params(description, normalizer.sha256(description), jobId).update();
        }
    }

    private void touch(long jobId, ParsedJobUrl url, RawJob raw) {
        attachSource(jobId, url, raw);
    }

    private Optional<Long> jobIdBy(String where, Object param) {
        return db.sql("SELECT job_id FROM job_source WHERE " + where + " LIMIT 1")
                 .param(param).query(Long.class).optional();
    }

    /**
     * Marks jobs that a source stopped reporting. A posting must go missing on two
     * consecutive successful scans before it counts as closed, so one flaky response
     * never closes a live job.
     */
    @Transactional
    public int closeMissing(long companyId, Instant scanStartedAt) {
        db.sql("""
               UPDATE job_source SET missing_count = missing_count + 1
                WHERE last_seen_at < ?
                  AND job_id IN (SELECT id FROM job WHERE company_id = ? AND state = 'OPEN')
               """).params(Timestamp.from(scanStartedAt), companyId).update();

        return db.sql("""
               UPDATE job SET state = 'CLOSED', closed_at = now(), updated_at = now()
                WHERE company_id = ? AND state = 'OPEN'
                  AND NOT EXISTS (
                      SELECT 1 FROM job_source s
                       WHERE s.job_id = job.id AND s.missing_count < ?)
               """).params(companyId, props.closeAfterMissingRuns()).update();
    }
}
