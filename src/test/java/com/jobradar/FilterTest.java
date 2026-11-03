package com.jobradar;

import com.jobradar.match.FilterProperties;
import com.jobradar.match.GermanRequirement;
import com.jobradar.match.HardFilters;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rules that decide what a user never sees. A false drop is invisible, so they matter.
 * Runs against a typical configuration: at most four years asked, German up to A2.
 */
class FilterTest {

    private final GermanRequirement german = new GermanRequirement();
    private final HardFilters filters = new HardFilters(german,
            new FilterProperties(4, GermanRequirement.Level.A2, null, null, null, null, null, null, null));

    @Test
    void germanLevelsAreReadFromBothLanguages() {
        assertThat(german.detect("Verhandlungssichere Deutschkenntnisse erforderlich"))
                .isEqualTo(GermanRequirement.Level.NATIVE);
        assertThat(german.detect("Fluent German is required")).isEqualTo(GermanRequirement.Level.C1);
        assertThat(german.detect("Sehr gute Deutschkenntnisse")).isEqualTo(GermanRequirement.Level.B2);
        assertThat(german.detect("Deutsch B1")).isEqualTo(GermanRequirement.Level.B1);
        assertThat(german.detect("Basic German is a plus")).isEqualTo(GermanRequirement.Level.A2);
        assertThat(german.detect("English is our company language"))
                .isEqualTo(GermanRequirement.Level.NONE);
    }

    @Test
    void onlyEnglishOrBasicGermanSurvives() {
        assertThat(filters.evaluate("Backend Engineer", "We work fully in English").keep()).isTrue();
        assertThat(filters.evaluate("Backend Engineer", "Grundkenntnisse in Deutsch").keep()).isTrue();
        assertThat(filters.evaluate("Backend Engineer", "Fließende Deutschkenntnisse").keep()).isFalse();
        assertThat(filters.evaluate("Backend Engineer", "Sehr gute Deutschkenntnisse").keep()).isFalse();
    }

    @Test
    void theFourYearCeilingHolds() {
        assertThat(filters.evaluate("Backend Engineer", "3+ years of experience").keep()).isTrue();
        assertThat(filters.evaluate("Backend Engineer", "4 years of professional experience").keep()).isTrue();
        assertThat(filters.evaluate("Backend Engineer", "5+ years of experience required").keep()).isFalse();
        assertThat(filters.evaluate("Backend Engineer", "mindestens 8 Jahre Berufserfahrung").keep()).isFalse();
    }

    @Test
    void nonEngineeringRolesNeverReachTheModel() {
        assertThat(filters.evaluate("Junior Cook/Koch", "kitchen work").keep()).isFalse();
        assertThat(filters.evaluate("Business Growth & GTM Manager", "").keep()).isFalse();
        assertThat(filters.evaluate("Data Analyst", "SQL and dashboards").keep()).isFalse();
        assertThat(filters.evaluate("Sales Development Representative", "").keep()).isFalse();
        assertThat(filters.evaluate("Engineering Intern", "").keep()).isFalse();
        assertThat(filters.evaluate("Werkstudent Softwareentwicklung", "").keep()).isFalse();
        assertThat(filters.evaluate("Working Student Software Engineering", "").keep()).isFalse();
        // "Development" alone is business, people or venture development, never code.
        assertThat(filters.evaluate("Business Development Representative - Germany", "").keep()).isFalse();
        assertThat(filters.evaluate("(Senior) People Development Partner", "").keep()).isFalse();
        assertThat(filters.evaluate("Venture Development Manager", "").keep()).isFalse();
        assertThat(filters.evaluate("Software Development Engineer in Test (SDET)", "").keep()).isTrue();
        assertThat(filters.evaluate("Backend Developer – Java & AI-first Software Development", "").keep()).isTrue();
    }

    @Test
    void seniorityCeilingDropsRolesSeveralStepsAhead() {
        assertThat(filters.evaluate("Staff Platform Engineer", "").keep()).isFalse();
        assertThat(filters.evaluate("Principal Data Engineer", "").keep()).isFalse();
        assertThat(filters.evaluate("Engineering Manager", "").keep()).isFalse();
        // "Senior" alone stays: plenty of German senior roles ask for three years.
        assertThat(filters.evaluate("Senior Backend Engineer", "3 years of experience").keep()).isTrue();
    }

    @Test
    void graduateProgrammesAreKeptWhenTheyAreActuallyTechnical() {
        // These are full-time entry jobs in Germany, and their titles often carry no
        // engineering word at all, so the positive gate alone would drop them.
        assertThat(filters.evaluate("Graduate Programme - Technology",
                "You will build software in Java with our engineering teams").keep()).isTrue();
        assertThat(filters.evaluate("Traineeprogramm IT",
                "Softwareentwicklung mit Java und Spring Boot").keep()).isTrue();
        assertThat(filters.evaluate("Berufseinsteiger",
                "Backend development with Kubernetes").keep()).isTrue();
    }

    @Test
    void graduateProgrammesOutsideEngineeringAreStillDropped() {
        assertThat(filters.evaluate("Graduate Programme - Sales",
                "You will grow our client base and hit quota").keep()).isFalse();
        assertThat(filters.evaluate("Trainee Marketing",
                "Campaigns and brand work").keep()).isFalse();
    }

    @Test
    void engineeringRolesAreKept() {
        assertThat(filters.evaluate("Java Entwickler (m/w/d)", "Spring Boot, Kubernetes").keep()).isTrue();
        assertThat(filters.evaluate("Backend Engineer - Payments", "").keep()).isTrue();
        assertThat(filters.evaluate("AI Engineer", "LangGraph, RAG").keep()).isTrue();
        assertThat(filters.evaluate("Forward Deployed Engineer", "").keep()).isTrue();
        assertThat(filters.evaluate("Softwareentwickler Java", "").keep()).isTrue();
    }
}
