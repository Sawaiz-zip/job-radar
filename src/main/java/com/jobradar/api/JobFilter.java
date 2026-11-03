package com.jobradar.api;

import java.util.List;
import java.util.Set;

/**
 * Everything the job list can be narrowed by, in one value.
 *
 * <p>It replaced a ten-argument {@code search(...)} where two adjacent booleans
 * ({@code germanyOnly, includeFiltered}) could be swapped without the compiler noticing.
 * Every field is optional; {@code null} or empty means "do not filter on this".
 *
 * @param terms      the tag bar: a job matches if ANY tag matches its title
 * @param text       the free-text box: title, company or location, combined with the tags by AND, so
 *                   typing narrows the tagged list instead of silently replacing it
 * @param city       substring of the location, case-insensitive ("münchen" finds "München, DE")
 * @param remoteOnly location mentions remote / home office
 * @param sourceKind "company" (the employer's own ATS) or "board" (Arbeitnow, BA, StepStone)
 * @param maxYears   hide jobs that ask for more years than this; jobs that state nothing stay
 * @param maxGerman  "A2", "B1" or "B2": hide jobs that ask for more German than this
 * @param hideApplied hide jobs already marked applied
 * @param freshness  "scan" or a number of hours, measured on when Job Radar first saw the job
 */
public record JobFilter(
        String state,
        String sort,
        List<String> terms,
        String text,
        String city,
        boolean remoteOnly,
        String sourceKind,
        Integer minChance,
        Integer minFit,
        Integer maxYears,
        String maxGerman,
        boolean hideApplied,
        boolean includeDismissed,
        boolean germanyOnly,
        boolean includeFiltered,
        String freshness,
        Integer postedWithinHours,
        Long companyId,
        int limit) {

    /** Boards that list many employers; everything else is an employer's own ATS. */
    public static final Set<String> BOARD_SOURCES = Set.of("ARBEITNOW", "BA", "STEPSTONE");

    /** CEFR levels in order, so "at most B1" is an index comparison. */
    static final List<String> GERMAN_LEVELS = List.of("A1", "A2", "B1", "B2", "C1", "C2", "native");

    /** German and English names of the cities the two kinds of source disagree on. */
    private static final List<List<String>> CITY_NAMES = List.of(
            List.of("münchen", "munich", "muenchen"),
            List.of("köln", "cologne", "koeln"),
            List.of("nürnberg", "nuremberg", "nuernberg"),
            List.of("düsseldorf", "dusseldorf", "duesseldorf"),
            List.of("frankfurt"),
            List.of("hannover", "hanover"));

    /** Every spelling of a typed city, or just the typed text when it is not a known city. */
    static List<String> cityVariants(String city) {
        String typed = city.trim().toLowerCase();
        for (List<String> names : CITY_NAMES) {
            if (names.stream().anyMatch(n -> n.startsWith(typed) || typed.startsWith(n))) {
                return typed.length() >= 3 ? names : List.of(typed);
            }
        }
        return List.of(typed);
    }

    /** The levels a {@code maxGerman} cap excludes, or empty for no cap or an unknown value. */
    static List<String> germanAbove(String maxGerman) {
        if (maxGerman == null || maxGerman.isBlank()) return List.of();
        int i = GERMAN_LEVELS.indexOf(maxGerman.trim().toUpperCase().replace("NATIVE", "native"));
        return i < 0 ? List.of() : GERMAN_LEVELS.subList(i + 1, GERMAN_LEVELS.size());
    }
}
