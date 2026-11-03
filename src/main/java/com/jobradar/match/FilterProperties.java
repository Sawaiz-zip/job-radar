package com.jobradar.match;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Who the radar is looking for, as configuration rather than code.
 *
 * <p>Everything a person would want to change about which jobs they see lives here, bound
 * from {@code jobradar.filters.*}. The defaults describe a software-engineering search with
 * no ceiling on experience or German, so a fresh checkout shows every engineering role and
 * each user tightens it to their own situation in {@code config/application-local.yml}.
 *
 * <p>Title lists are regular-expression fragments, matched case-insensitively.
 * {@code titleMustMatch} is matched anywhere in the title, with no word boundaries, because
 * German glues nouns together ("Softwareentwickler" must match "entwickl"). The exclude and
 * senior lists are matched as whole words.
 *
 * @param maxYearsRequired   drop postings that ask for more years than this; 0 means no limit
 * @param maxGermanLevel     drop postings that ask for more German than this; NATIVE means no limit
 * @param titleMustMatch     the title must contain one of these to count as a job in your field
 * @param titleExclude       titles containing one of these whole words are a different discipline
 * @param dropSeniorTitles   whether titles in {@code seniorTitles} are dropped
 * @param seniorTitles       titles several steps above the level you are applying at
 * @param includeStudentRoles keep Werkstudent, internship and apprenticeship roles
 * @param includeGraduateProgrammes keep graduate and trainee schemes whose text is technical
 * @param boosts             title patterns that raise a job's rule score, so they sort first
 */
@ConfigurationProperties(prefix = "jobradar.filters")
public record FilterProperties(
        Integer maxYearsRequired,
        GermanRequirement.Level maxGermanLevel,
        List<String> titleMustMatch,
        List<String> titleExclude,
        Boolean dropSeniorTitles,
        List<String> seniorTitles,
        Boolean includeStudentRoles,
        Boolean includeGraduateProgrammes,
        List<Boost> boosts
) {

    /** A title pattern worth extra points; negative points sort a match lower. */
    public record Boost(String pattern, int points) {}

    public static final List<String> DEFAULT_TITLE_MUST_MATCH = List.of(
            // No bare "development": alone it only ever admitted "Business Development
            // Representative" and "People Development Partner"; real engineering titles also
            // say engineer, software or developer.
            "engineer", "entwickl", "developer", "software", "backend", "back-end", "fullstack",
            "full-stack", "frontend", "programm", "sre\\b", "devops", "platform", "informatik",
            "technical staff", "technologist", "architek");

    public static final List<String> DEFAULT_TITLE_EXCLUDE = List.of(
            "data analyst", "datenanalyst", "data analytics", "business analyst", "business intelligence",
            "bi[ -]developer", "data scientist", "datascientist", "financial analyst",
            "account executive", "sales", "vertrieb", "recruiter", "recruiting", "talent acquisition",
            "marketing", "brand", "content writer", "copywriter", "customer success", "customer support",
            "kundenservice", "human resources", "people partner", "office manager",
            "designer", "ux", "ui designer", "product manager", "product owner", "projektleiter",
            "scrum master", "agile coach", "accountant", "controller", "buchhaltung", "legal counsel",
            "mechanical engineer", "electrical engineer", "thermal engineer", "hardware engineer",
            "test engineer manual", "qa manual", "nurse", "pflege", "driver", "fahrer", "logistics", "lager");

    public static final List<String> DEFAULT_SENIOR_TITLES = List.of(
            "staff", "principal", "head of", "director", "vp", "vice president", "chief", "cto",
            "team lead", "teamlead", "tech lead", "engineering manager", "architekt", "architect");

    /** Student and apprenticeship roles, dropped unless {@code includeStudentRoles} is set. */
    public static final List<String> STUDENT_ROLES = List.of(
            "werkstudent", "working student", "praktikum", "praktikant", "internship", "intern",
            "ausbildung", "duales studium");

    public FilterProperties {
        if (maxYearsRequired == null || maxYearsRequired < 0) maxYearsRequired = 0;
        if (maxGermanLevel == null || maxGermanLevel == GermanRequirement.Level.UNKNOWN) {
            maxGermanLevel = GermanRequirement.Level.NATIVE;
        }
        if (titleMustMatch == null || titleMustMatch.isEmpty()) titleMustMatch = DEFAULT_TITLE_MUST_MATCH;
        if (titleExclude == null) titleExclude = DEFAULT_TITLE_EXCLUDE;
        if (dropSeniorTitles == null) dropSeniorTitles = true;
        if (seniorTitles == null) seniorTitles = DEFAULT_SENIOR_TITLES;
        if (includeStudentRoles == null) includeStudentRoles = false;
        if (includeGraduateProgrammes == null) includeGraduateProgrammes = true;
        if (boosts == null) boosts = List.of();
    }

    /** Every default: an engineering search with no experience or German ceiling. */
    public static FilterProperties defaults() {
        return new FilterProperties(null, null, null, null, null, null, null, null, null);
    }

    public boolean hasYearsLimit() {
        return maxYearsRequired > 0;
    }

    public boolean hasGermanLimit() {
        return maxGermanLevel != GermanRequirement.Level.NATIVE;
    }
}
