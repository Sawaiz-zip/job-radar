package com.jobradar.ingest;

import com.jobradar.company.CompanyRepository;
import com.jobradar.dedupe.Normalizer;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

/**
 * Loads {@code companies.csv} and {@code search_terms.csv} from the seed directory
 * ({@code jobradar.seed-dir}, default {@code seed}) on startup. Both are idempotent:
 * importing the same file twice creates no duplicates (F1). Point the setting at your own
 * folder to keep a personal watchlist out of the shared defaults.
 */
@Component
public class SeedImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedImporter.class);

    private final CompanyRepository companies;
    private final Normalizer normalizer;
    private final JdbcClient db;
    private final ResourceLoader resources;
    private final String seedDir;

    public SeedImporter(CompanyRepository companies, Normalizer normalizer,
                        JdbcClient db, ResourceLoader resources,
                        @Value("${jobradar.seed-dir:seed}") String seedDir) {
        this.companies = companies;
        this.normalizer = normalizer;
        this.db = db;
        this.resources = resources;
        this.seedDir = seedDir == null || seedDir.isBlank() ? "seed" : seedDir.replaceAll("/+$", "");
    }

    @Override
    public void run(ApplicationArguments args) {
        importCompanies();
        importSearchTerms();
    }

    private void importCompanies() {
        Resource file = resources.getResource("file:" + seedDir + "/companies.csv");
        if (!file.exists()) {
            log.warn("{}/companies.csv not found - starting with an empty registry", seedDir);
            return;
        }
        int rows = 0;
        try (Reader r = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            for (CSVRecord rec : format().parse(r)) {
                String name = rec.get("name");
                if (name == null || name.isBlank()) continue;
                String normalized = value(rec, "normalized_name");
                companies.upsertSeed(
                        name.trim(),
                        normalized == null || normalized.isBlank() ? normalizer.normalizeCompany(name) : normalized,
                        value(rec, "category"), value(rec, "cities"), value(rec, "website"),
                        value(rec, "language"),
                        firstCharOr(value(rec, "priority"), "B"),
                        value(rec, "origin") == null ? "SEED" : value(rec, "origin"),
                        value(rec, "notes"));
                rows++;
            }
        } catch (Exception e) {
            log.error("company seed import failed: {}", e.toString());
            return;
        }
        Long total = db.sql("SELECT count(*) FROM company").query(Long.class).single();
        log.info("company seed: {} rows processed, {} companies in the registry", rows, total);
    }

    private void importSearchTerms() {
        Resource file = resources.getResource("file:" + seedDir + "/search_terms.csv");
        if (!file.exists()) return;
        int rows = 0;
        try (Reader r = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8)) {
            for (CSVRecord rec : format().parse(r)) {
                String text = value(rec, "text");
                if (text == null || text.isBlank()) continue;
                db.sql("""
                       INSERT INTO search_term (text, term_group, kind, active)
                       VALUES (?,?,?,?)
                       ON CONFLICT (text, kind) DO NOTHING
                       """)
                  .params(text.trim(), value(rec, "group"), value(rec, "kind"),
                          !"false".equalsIgnoreCase(value(rec, "active")))
                  .update();
                rows++;
            }
        } catch (Exception e) {
            log.error("search term seed import failed: {}", e.toString());
            return;
        }
        log.info("search term seed: {} rows processed", rows);
    }

    private static CSVFormat format() {
        return CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).setIgnoreSurroundingSpaces(true).build();
    }

    private static String value(CSVRecord rec, String column) {
        return rec.isMapped(column) ? emptyToNull(rec.get(column)) : null;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String firstCharOr(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.substring(0, 1).toUpperCase();
    }
}
