package com.jobradar.match;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap deterministic gate that runs before any LLM call. Two jobs at once: it keeps the
 * list free of roles the user does not want, and it keeps the LLM bill down by never
 * sending those jobs to a model at all.
 *
 * <p>What counts as "wanted" comes from {@link FilterProperties}; the matching rules below
 * (how years and German are read, how graduate schemes are recognised) are the same for
 * everyone.
 */
@Component
public class HardFilters {

    private static final Logger log = LoggerFactory.getLogger(HardFilters.class);

    private final GermanRequirement german;
    private final FilterProperties config;
    private final Pattern mustMatch;
    private final Pattern exclude;
    private final Pattern senior;
    private final Pattern student;
    private final List<CompiledBoost> boosts;

    private record CompiledBoost(Pattern pattern, int points) {}

    public HardFilters(GermanRequirement german, FilterProperties config) {
        this.german = german;
        this.config = config;
        this.mustMatch = anywhere(config.titleMustMatch());
        this.exclude = wholeWords(config.titleExclude());
        this.senior = wholeWords(config.seniorTitles());
        this.student = wholeWords(FilterProperties.STUDENT_ROLES);
        this.boosts = config.boosts().stream()
                .filter(b -> b.pattern() != null && !b.pattern().isBlank())
                .map(b -> new CompiledBoost(Pattern.compile(b.pattern(), Pattern.CASE_INSENSITIVE), b.points()))
                .toList();
        log.info("filters: max years {}, max German {}, senior titles {}, student roles {}, {} boosts",
                config.hasYearsLimit() ? config.maxYearsRequired() : "any",
                config.hasGermanLimit() ? config.maxGermanLevel() : "any",
                config.dropSeniorTitles() ? "dropped" : "kept",
                config.includeStudentRoles() ? "kept" : "dropped", boosts.size());
    }

    public FilterProperties config() {
        return config;
    }

    private static Pattern anywhere(List<String> fragments) {
        return fragments.isEmpty() ? null
                : Pattern.compile("(" + String.join("|", fragments) + ")", Pattern.CASE_INSENSITIVE);
    }

    private static Pattern wholeWords(List<String> fragments) {
        return fragments.isEmpty() ? null
                : Pattern.compile("\\b(" + String.join("|", fragments) + ")\\b", Pattern.CASE_INSENSITIVE);
    }

    private static boolean finds(Pattern p, String text) {
        return p != null && p.matcher(text).find();
    }

    /** "5+ years", "mindestens 5 Jahre", "5-7 years of experience". */
    private static final List<Pattern> YEARS = List.of(
            Pattern.compile("(\\d{1,2})\\s*\\+?\\s*(?:-\\s*\\d{1,2}\\s*)?(?:years?|jahre?n?)\\s*(?:of\\s*)?"
                          + "(?:relevant\\s*|professional\\s*|hands-on\\s*|work\\s*)?"
                          + "(?:experience|erfahrung|berufserfahrung)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:experience|erfahrung)\\D{0,25}?(\\d{1,2})\\s*\\+?\\s*(?:years?|jahre)",
                            Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?:mindestens|at least|minimum of)\\s*(\\d{1,2})\\s*(?:years?|jahre)",
                            Pattern.CASE_INSENSITIVE));

    /**
     * Graduate and entry programmes, which are full-time jobs in Germany however they are
     * worded. They often carry no engineering word in the title at all - "Graduate
     * Programme - Technology", "Traineeprogramm IT" - so the positive gate would drop
     * them. They are admitted only when the posting text is actually technical, which is
     * what keeps a sales trainee scheme out.
     */
    private static final Pattern GRADUATE_TITLE = Pattern.compile(
            "\\b(graduate|absolvent\\w*|berufseinsteiger\\w*|trainee\\w*|"
          + "einsteiger\\w*|new grad|early career|entry[- ]level|nachwuchs\\w*|"
          + "young professional|direkteinstieg)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TECHNICAL_CONTEXT = Pattern.compile(
            "\\b(java|spring|backend|software|entwickl\\w*|developer|engineering|"
          + "informatik\\w*|programmier\\w*|kubernetes|cloud|api|microservices|"
          + "python|kotlin|devops|it[- ]?(?:systeme?|abteilung)?)\\b",
            Pattern.CASE_INSENSITIVE);

    public record Verdict(boolean keep, int ruleScore, String reason, Integer yearsRequired,
                          GermanRequirement.Level germanLevel) {}

    /** Whether a German level is within the configured ceiling; UNKNOWN always passes. */
    public boolean acceptsGerman(GermanRequirement.Level level) {
        return level == GermanRequirement.Level.UNKNOWN || level.rank <= config.maxGermanLevel().rank;
    }

    /** Whether a years requirement is within the configured ceiling. */
    public boolean acceptsYears(int years) {
        return !config.hasYearsLimit() || years <= config.maxYearsRequired();
    }

    public Verdict evaluate(String title, String description) {
        String t = title == null ? "" : title;
        String text = (t + "\n" + (description == null ? "" : description)).toLowerCase(Locale.ROOT);

        boolean inField = finds(mustMatch, t);
        boolean graduateProgramme = config.includeGraduateProgrammes()
                && GRADUATE_TITLE.matcher(t).find()
                && TECHNICAL_CONTEXT.matcher(text).find();
        if (!inField && !graduateProgramme) {
            return new Verdict(false, 0, "title outside the configured field", null, GermanRequirement.Level.UNKNOWN);
        }
        if (finds(exclude, t)) {
            return new Verdict(false, 0, "a different discipline", null, GermanRequirement.Level.UNKNOWN);
        }
        if (!config.includeStudentRoles() && finds(student, t)) {
            return new Verdict(false, 0, "student or internship role", null, GermanRequirement.Level.UNKNOWN);
        }
        if (config.dropSeniorTitles() && finds(senior, t)) {
            return new Verdict(false, 0, "title above the configured seniority", null, GermanRequirement.Level.UNKNOWN);
        }

        OptionalInt years = requiredYears(text);
        Integer yearsBoxed = years.isPresent() ? years.getAsInt() : null;

        GermanRequirement.Level level = german.detect(text);
        if (!acceptsGerman(level)) {
            return new Verdict(false, 0, "needs German " + level.name(), yearsBoxed, level);
        }
        if (years.isPresent() && !acceptsYears(years.getAsInt())) {
            return new Verdict(false, 0, "asks for " + years.getAsInt() + "+ years", yearsBoxed, level);
        }

        int score = 50;
        for (CompiledBoost boost : boosts) {
            if (boost.pattern().matcher(t).find()) score += boost.points();
        }

        if (level == GermanRequirement.Level.NONE) score += 5;
        return new Verdict(true, Math.max(0, Math.min(score, 100)), null, yearsBoxed, level);
    }

    /** The smallest number of years the posting asks for; ranges count as their lower bound. */
    public OptionalInt requiredYears(String text) {
        int min = Integer.MAX_VALUE;
        for (Pattern p : YEARS) {
            Matcher m = p.matcher(text);
            while (m.find()) {
                try {
                    int v = Integer.parseInt(m.group(1));
                    if (v > 0 && v <= 20) min = Math.min(min, v);
                } catch (NumberFormatException ignored) {
                    // a malformed number is simply not a years requirement
                }
            }
        }
        return min == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(min);
    }
}
