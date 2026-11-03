package com.jobradar.company;

import com.jobradar.source.SourceType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CompanyRepository {

    private static final String COLS = """
            id, name, normalized_name, primary_domain, careers_url, ats_type, ats_tenant,
            ats_site, ats_region, category, cities, language, priority, status, enabled,
            origin, notes
            """;

    private final JdbcClient db;

    public CompanyRepository(JdbcClient db) {
        this.db = db;
    }

    private static Company map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Company(
                rs.getLong("id"), rs.getString("name"), rs.getString("normalized_name"),
                rs.getString("primary_domain"), rs.getString("careers_url"),
                SourceType.valueOf(rs.getString("ats_type")), rs.getString("ats_tenant"),
                rs.getString("ats_site"), rs.getString("ats_region"),
                rs.getString("category"), rs.getString("cities"),
                rs.getString("language"), rs.getString("priority"), rs.getString("status"),
                rs.getBoolean("enabled"), rs.getString("origin"), rs.getString("notes"));
    }

    public List<Company> findAllEnabled() {
        return db.sql("SELECT " + COLS + " FROM company WHERE enabled ORDER BY priority, name")
                 .query(CompanyRepository::map).list();
    }

    /** Only confirmed tenants are scanned: a guessed slug may belong to another company. */
    public List<Company> findScannable() {
        return db.sql("SELECT " + COLS + " FROM company WHERE enabled AND ats_tenant IS NOT NULL "
                    + "AND detection_confidence = 'CONFIRMED' ORDER BY priority, name")
                 .query(CompanyRepository::map).list();
    }

    /**
     * Companies worth probing for an ATS: enabled, no tenant, and never probed or probed
     * longer ago than {@code retryDays}. Never-probed first, then the most open jobs.
     */
    public List<Company> findDetectionCandidates(int limit, int retryDays) {
        return db.sql("SELECT " + COLS + " FROM company c WHERE enabled AND ats_tenant IS NULL "
                    + "AND (probed_at IS NULL OR probed_at < now() - make_interval(days => ?)) "
                    + "ORDER BY probed_at NULLS FIRST, "
                    + "(SELECT count(*) FROM job j WHERE j.company_id = c.id AND j.state = 'OPEN') DESC, "
                    + "priority, name LIMIT ?")
                 .params(retryDays, limit).query(CompanyRepository::map).list();
    }

    public void markProbed(long companyId) {
        db.sql("UPDATE company SET probed_at = now() WHERE id = ?").param(companyId).update();
    }

    public List<Company> findByConfidence(String confidence) {
        return db.sql("SELECT " + COLS + " FROM company WHERE detection_confidence = ? ORDER BY priority, name")
                 .param(confidence).query(CompanyRepository::map).list();
    }

    public void saveConfidence(long companyId, String confidence, String evidence) {
        db.sql("UPDATE company SET detection_confidence = ?, detection_evidence = ?, updated_at = now() WHERE id = ?")
          .params(confidence, evidence, companyId).update();
    }

    public void clearDetection(long companyId, String evidence) {
        db.sql("""
               UPDATE company SET ats_type = 'UNKNOWN', ats_tenant = NULL, ats_site = NULL,
                                  detection_confidence = 'REJECTED', detection_evidence = ?, updated_at = now()
                WHERE id = ?
               """).params(evidence, companyId).update();
    }

    public Optional<Company> findById(long id) {
        return db.sql("SELECT " + COLS + " FROM company WHERE id = ?")
                 .param(id).query(CompanyRepository::map).optional();
    }

    public Optional<Company> findByNormalizedName(String normalized) {
        return db.sql("SELECT " + COLS + " FROM company WHERE normalized_name = ?")
                 .param(normalized).query(CompanyRepository::map).optional();
    }

    public Optional<Company> findByAts(SourceType type, String tenant) {
        return db.sql("SELECT " + COLS + " FROM company WHERE ats_type = ? AND ats_tenant = ?")
                 .params(type.name(), tenant).query(CompanyRepository::map).optional();
    }

    /** Idempotent by normalized_name, so importing the same seed twice is a no-op. */
    public long upsertSeed(String name, String normalized, String category, String cities,
                           String website, String language, String priority, String origin, String notes) {
        return db.sql("""
                INSERT INTO company (name, normalized_name, category, cities, careers_url,
                                     language, priority, origin, notes)
                VALUES (?,?,?,?,?,?,?,?,?)
                ON CONFLICT (normalized_name) DO UPDATE
                   SET category = COALESCE(EXCLUDED.category, company.category),
                       cities   = COALESCE(EXCLUDED.cities, company.cities),
                       careers_url = COALESCE(company.careers_url, EXCLUDED.careers_url),
                       updated_at = now()
                RETURNING id
                """)
                .params(name, normalized, category, cities, website, language, priority, origin, notes)
                .query(Long.class).single();
    }

    public void saveDetection(long companyId, SourceType ats, String tenant, String site,
                              String careersUrl, String confidence, String evidence) {
        saveDetection(companyId, ats, tenant, site, null, careersUrl, confidence, evidence);
    }

    /** @param region Workday's data centre (wd1..wd12); null for every other ATS. */
    public void saveDetection(long companyId, SourceType ats, String tenant, String site,
                              String region, String careersUrl, String confidence, String evidence) {
        db.sql("""
               UPDATE company SET ats_type = ?, ats_tenant = ?, ats_site = ?,
                                  ats_region = COALESCE(?, ats_region),
                                  careers_url = COALESCE(careers_url, ?),
                                  detection_confidence = ?, detection_evidence = ?,
                                  detected_at = now(), updated_at = now()
               WHERE id = ?
               """)
          .params(ats.name(), tenant, site, region, careersUrl, confidence, evidence, companyId)
          .update();
    }

    public void recordRunOutcome(long companyId, boolean ok, int jobCount) {
        if (ok) {
            db.sql("""
                   UPDATE company SET last_success_at = now(), last_job_count = ?,
                                      consecutive_errors = 0, status = 'OK', updated_at = now()
                   WHERE id = ?
                   """).params(jobCount, companyId).update();
        } else {
            // 1-2 errors is DEGRADED, 3+ is FAILING (plan section 11).
            db.sql("""
                   UPDATE company
                      SET consecutive_errors = consecutive_errors + 1,
                          status = CASE WHEN consecutive_errors + 1 >= 3 THEN 'FAILING' ELSE 'DEGRADED' END,
                          updated_at = now()
                    WHERE id = ?
                   """).param(companyId).update();
        }
    }
}
