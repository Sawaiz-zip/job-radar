package com.jobradar.company;

import com.jobradar.dedupe.Normalizer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Board jobs arrive with an employer name and nothing else, so the company has to be
 * looked up or created per job. Name variants are the whole difficulty here: "Zalando SE",
 * "Zalando" and "ZALANDO SE" are one employer and must not become three registry rows.
 */
@Service
public class CompanyResolver {

    private final CompanyRepository companies;
    private final Normalizer normalizer;
    private final JdbcClient db;

    public CompanyResolver(CompanyRepository companies, Normalizer normalizer, JdbcClient db) {
        this.companies = companies;
        this.normalizer = normalizer;
        this.db = db;
    }

    /**
     * Folder names carry whatever the CV tool read off the posting, requisition codes and
     * all: "Amazon Web Services Development Center Germany GmbH R28". Those tails are not
     * part of the employer's name and would otherwise create a second company.
     */
    private static final java.util.regex.Pattern REQUISITION_TAIL = java.util.regex.Pattern.compile(
            "\\s+(?:[a-z]{1,2}\\d{2,}|\\d{3,}|req\\s*\\d+|job\\s*\\d+)$",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    @Transactional
    public long resolveOrCreate(String rawName, String origin) {
        String normalized = REQUISITION_TAIL.matcher(normalizer.normalizeCompany(rawName))
                                            .replaceAll("").trim();
        if (normalized.isBlank()) normalized = "unknown employer";

        Optional<Company> byName = companies.findByNormalizedName(normalized);
        if (byName.isPresent()) {
            rememberAlias(byName.get().id(), rawName, normalized);
            return byName.get().id();
        }

        Optional<Long> byAlias = db.sql("SELECT company_id FROM company_alias WHERE alias_normalized = ?")
                .param(normalized).query(Long.class).optional();
        if (byAlias.isPresent()) return byAlias.get();

        // Near-identical names are the same employer in practice: "Amazon Web Services
        // Development Center Germany" against the same name with a requisition tail, or one
        // that contains the other. Trigram similarity catches both without a second row.
        Optional<Long> similar = db.sql("""
                SELECT id FROM company
                 WHERE similarity(normalized_name, ?) >= 0.90
                    OR normalized_name LIKE ? || '%'
                    OR ? LIKE normalized_name || '%'
                 ORDER BY similarity(normalized_name, ?) DESC
                 LIMIT 1
                """)
                .params(normalized, normalized, normalized, normalized)
                .query(Long.class).optional();
        if (similar.isPresent()) {
            rememberAlias(similar.get(), rawName, normalized);
            return similar.get();
        }

        long id = companies.upsertSeed(rawName == null ? "Unknown employer" : rawName.trim(),
                normalized, null, null, null, null, "B", origin, "first seen on a job board");
        rememberAlias(id, rawName, normalized);
        return id;
    }

    private void rememberAlias(long companyId, String rawName, String normalized) {
        if (rawName == null || rawName.isBlank()) return;
        db.sql("""
               INSERT INTO company_alias (company_id, alias_normalized)
               VALUES (?, ?) ON CONFLICT (alias_normalized) DO NOTHING
               """).params(companyId, normalized).update();
    }
}
