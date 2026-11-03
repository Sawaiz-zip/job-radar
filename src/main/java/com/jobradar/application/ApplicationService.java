package com.jobradar.application;

import com.jobradar.company.CompanyResolver;
import com.jobradar.dedupe.Normalizer;
import com.jobradar.dedupe.UrlCanonicalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Everything the user has applied for. Applications are recorded from the UI; optionally,
 * history can be backfilled from a folder of {@code CV-<company>-<yyyy>-<mm>-<dd>} directories
 * written by a CV generator ({@code jobradar.cv-output-dir}).
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    /** CV-Acme-Robotics-2026-09-17 and CV-Acme-2026-08-14-2 (a second attempt). */
    private static final Pattern FOLDER =
            Pattern.compile("^CV-(.+?)-(\\d{4})-(\\d{2})-(\\d{2})(?:-(\\d+))?$");

    private final JdbcClient db;
    private final CompanyResolver resolver;
    private final Normalizer normalizer;
    private final UrlCanonicalizer canonicalizer;
    private final String cvOutputDir;

    public ApplicationService(JdbcClient db, CompanyResolver resolver, Normalizer normalizer,
                              UrlCanonicalizer canonicalizer,
                              @Value("${jobradar.cv-output-dir:}") String cvOutputDir) {
        this.db = db;
        this.resolver = resolver;
        this.normalizer = normalizer;
        this.canonicalizer = canonicalizer;
        this.cvOutputDir = cvOutputDir;
    }

    public record BackfillResult(int foldersSeen, int imported, int alreadyPresent,
                                 int companiesCreated, List<String> skipped) {}

    /**
     * Rebuilds application history from the CV folders. Only the company and the date are
     * recoverable - the job URL was never stored and the role name only appears inside the
     * cover-letter prose, where extracting it guessed wrong more often than right - so the
     * role is left blank rather than invented.
     */
    public BackfillResult backfillFromCvFolders() {
        if (cvOutputDir == null || cvOutputDir.isBlank()) {
            return new BackfillResult(0, 0, 0, 0, List.of("jobradar.cv-output-dir is not set"));
        }
        Path root = Path.of(cvOutputDir);
        if (!Files.isDirectory(root)) {
            return new BackfillResult(0, 0, 0, 0, List.of("CV output directory not found: " + root.toAbsolutePath()));
        }

        int seen = 0, imported = 0, present = 0, companiesCreated = 0;
        List<String> skipped = new ArrayList<>();
        long companiesBefore = db.sql("SELECT count(*) FROM company").query(Long.class).single();

        try (Stream<Path> entries = Files.list(root)) {
            List<Path> folders = entries.filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith("CV-"))
                    .sorted()
                    .toList();

            for (Path folder : folders) {
                String name = folder.getFileName().toString();
                Matcher m = FOLDER.matcher(name);
                if (!m.matches()) {
                    skipped.add(name + " (unrecognised folder name)");
                    continue;
                }
                seen++;
                String company = m.group(1).replace('-', ' ').trim();
                LocalDate date = LocalDate.of(Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)));

                long companyId = resolver.resolveOrCreate(company, "CV_HISTORY");
                Long jobId = matchExistingJob(companyId, folder);

                int rows = db.sql("""
                        INSERT INTO application (company_id, job_id, title, applied_at, source, folder, status)
                        VALUES (?,?,?,?,'CV_HISTORY',?,'Applied')
                        ON CONFLICT (folder) WHERE folder IS NOT NULL DO NOTHING
                        """)
                        .params(companyId, jobId, null,
                                Timestamp.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()),
                                name)
                        .update();
                if (rows > 0) {
                    imported++;
                    if (jobId != null) {
                        db.sql("UPDATE job SET cv_generated = TRUE, cv_generated_at = COALESCE(cv_generated_at, ?) WHERE id = ?")
                          .params(Timestamp.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()), jobId)
                          .update();
                    }
                } else {
                    present++;
                }
            }
        } catch (IOException e) {
            skipped.add("could not read " + root + ": " + e.getMessage());
        }

        long companiesAfter = db.sql("SELECT count(*) FROM company").query(Long.class).single();
        companiesCreated = (int) (companiesAfter - companiesBefore);
        log.info("backfill: {} folders, {} imported, {} already present, {} new companies",
                seen, imported, present, companiesCreated);
        return new BackfillResult(seen, imported, present, companiesCreated, skipped);
    }

    /**
     * A past application can sometimes be tied to a job still on the board - the same role
     * reposted, or one we scraped later. Only an unambiguous single open job counts; guessing
     * would attach a rejection email to the wrong posting.
     */
    private Long matchExistingJob(long companyId, Path folder) {
        List<Long> open = db.sql(
                "SELECT id FROM job WHERE company_id = ? AND state = 'OPEN' AND cv_generated = FALSE")
                .param(companyId).query(Long.class).list();
        return open.size() == 1 ? open.get(0) : null;
    }

    /** Records an application the CV tool just produced. */
    public Map<String, Object> record(String company, String url, String title, String folder) {
        if (company == null || company.isBlank()) {
            return Map.of("ok", false, "message", "a company name is required");
        }
        long companyId = resolver.resolveOrCreate(company, "CV_TOOL");

        Long jobId = null;
        if (url != null && !url.isBlank()) {
            String canonical = canonicalizer.parse(url).canonicalUrl();
            jobId = db.sql("SELECT job_id FROM job_source WHERE canonical_url = ? LIMIT 1")
                      .param(canonical).query(Long.class).optional().orElse(null);
        }
        if (jobId == null && title != null && !title.isBlank()) {
            jobId = db.sql("""
                    SELECT id FROM job WHERE company_id = ? AND title ILIKE ? LIMIT 1
                    """).params(companyId, "%" + title.trim() + "%")
                    .query(Long.class).optional().orElse(null);
        }

        if (jobId != null) {
            db.sql("""
                   UPDATE job SET cv_generated = TRUE, cv_generated_at = COALESCE(cv_generated_at, now()),
                                  applied_at = COALESCE(applied_at, now()), updated_at = now()
                    WHERE id = ?
                   """).param(jobId).update();
        }

        if (folder != null && !folder.isBlank()) {
            db.sql("""
                   INSERT INTO application (company_id, job_id, title, applied_at, source, folder, status)
                   VALUES (?,?,?,now(),'CV_TOOL',?,'Applied')
                   ON CONFLICT (folder) WHERE folder IS NOT NULL
                   DO UPDATE SET job_id = COALESCE(application.job_id, EXCLUDED.job_id),
                                 title  = COALESCE(application.title, EXCLUDED.title)
                   """)
              .params(companyId, jobId, title, folder)
              .update();
        } else {
            // No folder to key on, so avoid a duplicate by looking for the same job first.
            boolean exists = jobId != null && db.sql(
                    "SELECT count(*) FROM application WHERE job_id = ?")
                    .param(jobId).query(Long.class).single() > 0;
            if (!exists) {
                db.sql("""
                       INSERT INTO application (company_id, job_id, title, applied_at, source, status)
                       VALUES (?,?,?,now(),'CV_TOOL','Applied')
                       """).params(companyId, jobId, title).update();
            }
        }

        return Map.of("ok", true, "companyId", companyId,
                      "jobId", jobId == null ? "" : jobId,
                      "linkedToJob", jobId != null);
    }

    public List<Map<String, Object>> list() {
        return db.sql("""
                SELECT a.id, c.name AS company, a.title, a.applied_at, a.source, a.folder,
                       a.status, a.response_status, a.response_at, a.response_subject,
                       a.response_snippet, a.response_checked_at, j.apply_url
                  FROM application a
                  JOIN company c ON c.id = a.company_id
                  LEFT JOIN job j ON j.id = a.job_id
                 ORDER BY a.applied_at DESC, c.name
                """).query().listOfRows();
    }
}
