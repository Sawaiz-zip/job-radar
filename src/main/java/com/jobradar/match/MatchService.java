package com.jobradar.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns stored jobs into two numbers: fit (does this match what the candidate does) and
 * chance (would they actually get an interview). They are kept separate on purpose - a
 * Staff role at a frontier lab can be high fit and near-zero chance, and only the second
 * one says where an afternoon of writing an application is worth spending.
 */
@Service
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private static final String SYSTEM_HEAD = """
            You score job postings for one specific candidate. You are blunt and calibrated:
            most jobs are a poor bet and should score low. Never flatter.

            Return ONLY a JSON object with exactly these keys:
            {
              "fit_score": 0-100,          // overlap between the candidate's real skills and the job's asks
              "chance_score": 0-100,       // realistic probability this candidate gets an interview
              "reason": "one sentence, max 20 words, why chance_score is what it is",
              "german_level": "none|A2|B1|B2|C1|native|unknown",
              "years_required": 0,         // smallest number of years the posting demands, 0 if unstated
              "seniority": "junior|mid|senior|lead",
              "stack_overlap": ["skill the posting asks for that the candidate has"],
              "missing_skills": ["skill the posting asks for that the candidate lacks"],
              "visa_note": "",             // only if the posting mentions citizenship, clearance or visa limits
              "deal_breaker": ""           // short phrase if this job is not worth applying to, else ""
            }

            Scoring rules that override your instincts:
            - Report german_level honestly, including when you infer it from the posting being
              written in German.
            - Roles outside the candidate's field, judged from the profile, get fit_score below 20
              and a deal_breaker naming the discipline.
            - Security clearance, citizenship requirements or defence roles restricted to EU
              nationals: say so in visa_note and cut chance_score accordingly.
            """;

    /** The fixed instructions plus the rules that follow from this user's filter settings. */
    static String systemPrompt(FilterProperties f, String idealMatch) {
        StringBuilder sb = new StringBuilder(SYSTEM_HEAD);
        if (f.hasYearsLimit()) {
            sb.append("- A posting demanding more than ").append(f.maxYearsRequired())
              .append(" years gets chance_score below 25, whatever else it offers.\n");
        }
        if (f.hasGermanLimit()) {
            sb.append("- German above ").append(f.maxGermanLevel().name())
              .append(" gets chance_score below 25: the candidate does not speak German at that level.\n");
        }
        if (f.dropSeniorTitles()) {
            sb.append("- Staff / Principal / Lead / Head / Architect titles get chance_score below 20.\n");
        }
        if (idealMatch != null && !idealMatch.isBlank()) {
            sb.append("- A strong match is: ").append(idealMatch.trim()).append("\n");
        }
        return sb.toString();
    }

    private final JdbcClient db;
    private final OpenRouterClient llm;
    private final CandidateProfile profile;
    private final HardFilters filters;
    private final String system;
    private final ObjectMapper json = new ObjectMapper();
    private final double maxEstimatedCost;
    /** Off by default: rules only, no model calls, no spend. */
    private final boolean scoringEnabled;

    /** Jobs scored before the run's total is projected from what they really cost. */
    private static final int COST_SAMPLE = 5;

    public MatchService(JdbcClient db, OpenRouterClient llm, CandidateProfile profile, HardFilters filters,
                        @org.springframework.beans.factory.annotation.Value(
                                "${jobradar.llm.max-estimated-cost:0.50}") double maxEstimatedCost,
                        @org.springframework.beans.factory.annotation.Value(
                                "${jobradar.llm.scoring-enabled:false}") boolean scoringEnabled,
                        @org.springframework.beans.factory.annotation.Value(
                                "${jobradar.llm.ideal-match:}") String idealMatch) {
        this.db = db;
        this.llm = llm;
        this.profile = profile;
        this.filters = filters;
        this.maxEstimatedCost = maxEstimatedCost;
        this.scoringEnabled = scoringEnabled;
        this.system = systemPrompt(filters.config(), idealMatch);
    }

    public record ScoreRun(int considered, int filteredOut, int scored, int failed,
                           double costUsd, int promptTokens, int completionTokens, String stoppedBecause) {}

    /**
     * Scores jobs that have no score yet. Cheap rules run first and most jobs never reach
     * the model, which is what keeps a scan's cost in cents rather than dollars.
     */
    public ScoreRun scoreNewJobs(int limit) {
        llm.resetSpend();
        List<Map<String, Object>> jobs = db.sql("""
                SELECT j.id, j.title, j.description_text, j.description_hash, j.location_raw, c.name AS company
                  FROM job j
                  JOIN company c ON c.id = j.company_id
                  LEFT JOIN job_match m ON m.job_id = j.id
                 WHERE j.state = 'OPEN' AND j.in_germany AND NOT j.dismissed
                   AND (m.job_id IS NULL OR m.description_hash IS DISTINCT FROM j.description_hash)
                 ORDER BY COALESCE(j.posted_at, j.first_seen_at) DESC
                 LIMIT ?
                """).param(limit).query().listOfRows();

        int filteredOut = 0, scored = 0, failed = 0, promptTokens = 0, completionTokens = 0;
        String stopped = null;

        // The free rules run on everything first, so the model is only ever asked about
        // jobs that survived them - and the cost check below counts exactly those.
        List<Map<String, Object>> forModel = new java.util.ArrayList<>();
        List<HardFilters.Verdict> verdicts = new java.util.ArrayList<>();
        for (Map<String, Object> job : jobs) {
            long id = ((Number) job.get("id")).longValue();
            HardFilters.Verdict verdict = filters.evaluate((String) job.get("title"),
                                                           (String) job.get("description_text"));
            if (!verdict.keep()) {
                storeRejected(id, job, verdict);
                filteredOut++;
            } else if (!scoringEnabled || !llm.isConfigured()) {
                storeRulesOnly(id, job, verdict);
            } else {
                forModel.add(job);
                verdicts.add(verdict);
            }
        }

        for (int i = 0; i < forModel.size(); i++) {
            Map<String, Object> job = forModel.get(i);
            HardFilters.Verdict verdict = verdicts.get(i);
            long id = ((Number) job.get("id")).longValue();

            // After a small sample, project the whole run from what those calls really cost
            // (OpenRouter reports the dollar amount per response). Over the cap, the rest is
            // left unscored rather than half-done: the jobs stay visible, and a later run with
            // a smaller backlog - or a raised cap - picks them up.
            if (i == COST_SAMPLE && scored > 0) {
                double perJob = llm.spentThisRun() / scored;
                double projected = perJob * forModel.size();
                if (projected > maxEstimatedCost) {
                    stopped = "AI scoring skipped: %d jobs would cost about $%.2f (cap $%.2f); scored a sample of %d"
                            .formatted(forModel.size(), projected, maxEstimatedCost, scored);
                    log.warn(stopped);
                    break;
                }
            }

            try {
                OpenRouterClient.Answer answer = llm.complete(system, prompt(job, verdict), 500);
                promptTokens += answer.promptTokens();
                completionTokens += answer.completionTokens();
                store(id, job, verdict, json.readTree(answer.content()));
                scored++;
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().startsWith("spend guard")) {
                    stopped = e.getMessage();
                    break;
                }
                log.warn("scoring failed for job {}: {}", id, e.toString());
                markFailed(id, job);
                failed++;
            } catch (Exception e) {
                log.warn("unreadable model output for job {}: {}", id, e.toString());
                markFailed(id, job);
                failed++;
            }
        }

        log.info("scored {} jobs, {} filtered out by rules, {} failed, ${} spent",
                scored, filteredOut, failed, String.format("%.4f", llm.spentThisRun()));
        return new ScoreRun(jobs.size(), filteredOut, scored, failed,
                llm.spentThisRun(), promptTokens, completionTokens, stopped);
    }

    private String prompt(Map<String, Object> job, HardFilters.Verdict verdict) {
        String description = (String) job.get("description_text");
        if (description == null) description = "(no description available)";
        // The model does not need the whole posting; requirements live near the top.
        if (description.length() > 2400) description = description.substring(0, 2400);

        return """
               CANDIDATE
               %s

               JOB
               Company: %s
               Title: %s
               Location: %s
               Years detected by rules: %s
               Description:
               %s
               """.formatted(profile.summary(), job.get("company"), job.get("title"),
                             job.get("location_raw"),
                             verdict.yearsRequired() == null ? "not stated" : verdict.yearsRequired(),
                             description);
    }

    private void store(long id, Map<String, Object> job, HardFilters.Verdict verdict, JsonNode r) {
        String dealBreaker = r.path("deal_breaker").asText("");
        int chance = clamp(r.path("chance_score").asInt(0));
        if (!dealBreaker.isBlank()) chance = Math.min(chance, 20);

        // Second language gate, on the model's own answer. The rules only catch an explicit
        // phrase; the model also spots a posting that is simply written in German throughout.
        String reportedGerman = r.path("german_level").asText("unknown");
        GermanRequirement.Level reported = parseLevel(reportedGerman);
        if (!filters.acceptsGerman(reported)) {
            upsert(id, job, verdict.ruleScore(), clamp(r.path("fit_score").asInt(0)), Math.min(chance, 15),
                    "needs German " + reported.name(), reportedGerman,
                    r.path("years_required").asInt(0), arr(r.path("stack_overlap")),
                    arr(r.path("missing_skills")), "German " + reported.name() + " required", "FILTERED");
            return;
        }

        int years = r.path("years_required").asInt(0);
        if (!filters.acceptsYears(years)) {
            upsert(id, job, verdict.ruleScore(), clamp(r.path("fit_score").asInt(0)), Math.min(chance, 15),
                    "asks for " + years + " years", reportedGerman, years, arr(r.path("stack_overlap")),
                    arr(r.path("missing_skills")), years + " years required", "FILTERED");
            return;
        }

        upsert(id, job, verdict.ruleScore(), clamp(r.path("fit_score").asInt(0)), chance,
                r.path("reason").asText(""), r.path("german_level").asText("unknown"),
                r.path("years_required").asInt(0), arr(r.path("stack_overlap")),
                arr(r.path("missing_skills")), dealBreaker, "OK");
    }

    private void storeRejected(long id, Map<String, Object> job, HardFilters.Verdict v) {
        upsert(id, job, 0, 0, 0, v.reason(), "unknown",
                v.yearsRequired() == null ? 0 : v.yearsRequired(), "[]", "[]", v.reason(), "FILTERED");
    }

    private void storeRulesOnly(long id, Map<String, Object> job, HardFilters.Verdict v) {
        upsert(id, job, v.ruleScore(), v.ruleScore(), v.ruleScore(),
                scoringEnabled ? "rule score only - no model configured" : "rule score only - AI scoring is off", "unknown",
                v.yearsRequired() == null ? 0 : v.yearsRequired(), "[]", "[]", "", "RULES_ONLY");
    }

    private void markFailed(long id, Map<String, Object> job) {
        upsert(id, job, 0, null, null, "scoring failed", "unknown", 0, "[]", "[]", "", "ENRICH_FAILED");
    }

    private void upsert(long jobId, Map<String, Object> job, Integer ruleScore, Integer fit, Integer chance,
                        String reason, String german, int years, String stack, String missing,
                        String dealBreaker, String state) {
        db.sql("""
               INSERT INTO job_match (job_id, rule_score, fit_score, chance_score, reason, german_level,
                                      years_required, stack_overlap, missing_skills, flags,
                                      description_hash, model, state)
               VALUES (?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?,?)
               ON CONFLICT (job_id) DO UPDATE SET
                   rule_score = EXCLUDED.rule_score, fit_score = EXCLUDED.fit_score,
                   chance_score = EXCLUDED.chance_score, reason = EXCLUDED.reason,
                   german_level = EXCLUDED.german_level, years_required = EXCLUDED.years_required,
                   stack_overlap = EXCLUDED.stack_overlap, missing_skills = EXCLUDED.missing_skills,
                   flags = EXCLUDED.flags, description_hash = EXCLUDED.description_hash,
                   model = EXCLUDED.model, state = EXCLUDED.state, created_at = now()
               """)
          .params(jobId, ruleScore, fit, chance, reason, german, years, stack, missing,
                  dealBreaker.isBlank() ? "[]" : json.createArrayNode().add(dealBreaker).toString(),
                  job.get("description_hash"), "deepseek-v4-flash", state)
          .update();
    }

    private static GermanRequirement.Level parseLevel(String raw) {
        if (raw == null) return GermanRequirement.Level.UNKNOWN;
        try {
            return GermanRequirement.Level.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return GermanRequirement.Level.UNKNOWN;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private String arr(JsonNode node) {
        return node.isArray() ? node.toString() : "[]";
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scored", db.sql("SELECT count(*) FROM job_match WHERE state = 'OK'").query(Long.class).single());
        out.put("filtered", db.sql("SELECT count(*) FROM job_match WHERE state = 'FILTERED'").query(Long.class).single());
        out.put("unscored", db.sql("""
                SELECT count(*) FROM job j LEFT JOIN job_match m ON m.job_id = j.id
                 WHERE j.state='OPEN' AND j.in_germany AND m.job_id IS NULL
                """).query(Long.class).single());
        return out;
    }

    public List<String> modelInfo() {
        List<String> info = new ArrayList<>();
        info.add(!scoringEnabled ? "AI scoring off (rules only)"
                 : llm.isConfigured() ? "llm configured" : "llm NOT configured");
        info.add(profile.isLoaded() ? "profile loaded" : "profile fallback");
        return info;
    }
}
