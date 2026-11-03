package com.jobradar;

import com.jobradar.dedupe.Normalizer;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizerTest {

    private final Normalizer normalizer = new Normalizer();

    @Test
    void germanGenderTagsNeverMakeADuplicate() {
        String a = normalizer.normalizeTitle("Java Entwickler (m/w/d)");
        assertThat(normalizer.normalizeTitle("Java Entwickler (w/m/d)")).isEqualTo(a);
        assertThat(normalizer.normalizeTitle("Java Entwickler (all genders)")).isEqualTo(a);
        assertThat(normalizer.normalizeTitle("Java Entwickler*")).isEqualTo(a);
        // Spelled-out variants: an earlier regex only accepted single letters and let
        // "Backend Developer (m/w/div.)" keep its tag.
        assertThat(normalizer.normalizeTitle("Java Entwickler (m/w/div.)")).isEqualTo(a);
        assertThat(normalizer.normalizeTitle("Java Entwickler (m/w/divers)")).isEqualTo(a);
        assertThat(normalizer.cleanTitle("Backend Developer (m/w/div.)")).isEqualTo("Backend Developer");
    }

    @Test
    void senioritySurvivesForDisplayButNotForTheFingerprint() {
        assertThat(normalizer.cleanTitle("Senior Backend Engineer (m/w/d)")).isEqualTo("Senior Backend Engineer");
        assertThat(normalizer.normalizeTitle("Senior Backend Engineer"))
                .isEqualTo(normalizer.normalizeTitle("Backend Engineer"));
    }

    @Test
    void umlautsAreTransliterated() {
        assertThat(normalizer.normalizeTitle("Softwareentwickler für Schnittstellen"))
                .isEqualTo("softwareentwickler fuer schnittstellen");
    }

    @Test
    void citiesMapToOneCanonicalForm() {
        assertThat(normalizer.normalizeCity("München")).isEqualTo("munich");
        assertThat(normalizer.normalizeCity("Munich, Germany")).isEqualTo("munich");
        assertThat(normalizer.normalizeCity("Frankfurt am Main")).isEqualTo("frankfurt");
        assertThat(normalizer.normalizeCity("Remote - Germany")).isEqualTo("remote-de");
    }

    @Test
    void multipleCitiesAreOrderIndependent() {
        assertThat(normalizer.normalizeCity("Berlin, Munich")).isEqualTo(normalizer.normalizeCity("Munich, Berlin"));
    }

    @Test
    void legalFormsDoNotSplitOneCompanyInTwo() {
        String zalando = normalizer.normalizeCompany("Zalando SE");
        assertThat(normalizer.normalizeCompany("Zalando")).isEqualTo(zalando);
        assertThat(normalizer.normalizeCompany("ZALANDO SE ")).isEqualTo(zalando);
    }

    @Test
    void fingerprintIsStableAcrossSourcesAndDistinctAcrossJobs() {
        long company = 42L;
        assertThat(normalizer.fingerprint(company, "Backend Engineer (m/w/d)", "München"))
                .isEqualTo(normalizer.fingerprint(company, "Senior Backend Engineer", "Munich, Germany"));
        assertThat(normalizer.fingerprint(company, "Backend Engineer", "Munich"))
                .isNotEqualTo(normalizer.fingerprint(company, "Frontend Engineer", "Munich"));
        assertThat(normalizer.fingerprint(company, "Backend Engineer", "Munich"))
                .isNotEqualTo(normalizer.fingerprint(43L, "Backend Engineer", "Munich"));
    }
}
