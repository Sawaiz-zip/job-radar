package com.jobradar.source;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Trimmed from a real stepstone.de/jobs/java-backend page and a real detail page
 * (2026-10-03). Parsing only - no network: the client and rate limiter are never touched.
 */
class StepStoneAdapterTest {

    private final StepStoneAdapter adapter = new StepStoneAdapter(null, null, List.of("java-backend"), 10);

    private static final String RESULT_PAGE = """
            <html><head><title>Java Backend Jobs | Stepstone</title></head><body>
            <script>window.__PRELOADED_STATE__["app-unifiedResultlist"] = {"searchResults":{"items":[
              {"id":14034976,"title":"Softwareentwickler Backend Java (m/w/d) im Bereich IoT",
               "url":"/stellenangebote--Softwareentwickler-Backend-Java-Wuerzburg-Baeren-io-GmbH--14034976-inline.html?rltr=1_1_25_seorl",
               "companyName":"Baeren.io GmbH","datePosted":"2026-10-02T12:03:07+02:00",
               "location":"Würzburg","isAnonymous":false},
              {"id":14000001,"title":"Java Developer","url":"/stellenangebote--x--14000001-inline.html",
               "companyName":"","datePosted":"2026-10-01T09:00:00+02:00","location":"Berlin","isAnonymous":true},
              {"id":14034976,"title":"duplicate on the same page","url":"/x","companyName":"Baeren.io GmbH"}
            ]}};
            window.__PRELOADED_STATE__["other"] = {};</script></body></html>
            """;

    @Test
    void readsTheEmbeddedResultList() {
        Set<String> seen = new LinkedHashSet<>();
        FetchResult result = adapter.parseResultList(RESULT_PAGE, seen);

        assertThat(result.isError()).isFalse();
        // The anonymous ad has no employer, and the repeated id is the same posting.
        assertThat(result.jobs()).hasSize(1);
        RawJob job = result.jobs().get(0);
        assertThat(job.sourceType()).isEqualTo(SourceType.STEPSTONE);
        assertThat(job.externalId()).isEqualTo("14034976");
        assertThat(job.companyName()).isEqualTo("Baeren.io GmbH");
        assertThat(job.location()).isEqualTo("Würzburg");
        assertThat(job.postedAt()).isNotNull();
        // The tracking query is dropped, so the same posting always has the same URL.
        assertThat(job.url()).isEqualTo("https://www.stepstone.de/stellenangebote--"
                + "Softwareentwickler-Backend-Java-Wuerzburg-Baeren-io-GmbH--14034976-inline.html");
    }

    @Test
    void aPageWithoutTheDataIsAFailureNotAnEmptyResult() {
        FetchResult result = adapter.parseResultList("<html><body>Access denied</body></html>", new LinkedHashSet<>());
        assertThat(result.isError()).isTrue();
    }

    @Test
    void readsTheDescriptionFromTheJobPostingSchema() {
        String detail = """
                <html><head>
                <script type="application/ld+json">{"@type":"BreadcrumbList","itemListElement":[]}</script>
                <script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting",
                  "title":"Softwareentwickler Backend Java",
                  "description":"<h4>Einleitung</h4>\\n<p>Wir suchen Java und <b>Spring Boot</b>.</p>"}</script>
                </head><body></body></html>
                """;
        assertThat(adapter.jobPostingDescription(detail))
                .isEqualTo("Einleitung Wir suchen Java und Spring Boot.");
    }
}
