package com.jobradar;

import com.jobradar.dedupe.ParsedJobUrl;
import com.jobradar.dedupe.UrlCanonicalizer;
import com.jobradar.source.SourceType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-driven, because the whole point of canonicalization is that many URL shapes
 * collapse to one key. Each group below is one real posting seen several ways.
 */
class UrlCanonicalizerTest {

    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

    @Test
    void greenhouseVariantsCollapseToOneKey() {
        String expected = "greenhouse:parloa:4656966101";
        assertThat(key("https://job-boards.eu.greenhouse.io/parloa/jobs/4656966101")).isEqualTo(expected);
        assertThat(key("https://boards.greenhouse.io/parloa/jobs/4656966101")).isEqualTo(expected);
        assertThat(key("https://job-boards.greenhouse.io/parloa/jobs/4656966101?gh_src=abc")).isEqualTo(expected);
        assertThat(key("https://JOB-BOARDS.greenhouse.io/parloa/jobs/4656966101#apply")).isEqualTo(expected);
    }

    @Test
    void leverApplySuffixAndTrackingParamsAreIgnored() {
        String expected = "lever:netlight:7f3c1b2a-0000-4a11-9c2e-111122223333";
        assertThat(key("https://jobs.lever.co/netlight/7f3c1b2a-0000-4a11-9c2e-111122223333")).isEqualTo(expected);
        assertThat(key("https://jobs.lever.co/netlight/7f3c1b2a-0000-4a11-9c2e-111122223333?lever-source=LinkedIn"))
                .isEqualTo(expected);
    }

    @Test
    void linkedInSearchAndViewUrlsAreTheSameJob() {
        assertThat(key("https://www.linkedin.com/jobs/view/3912345678")).isEqualTo("linkedin_link::3912345678");
        assertThat(key("https://www.linkedin.com/jobs/search?currentJobId=3912345678&keywords=java"))
                .isEqualTo("linkedin_link::3912345678");
    }

    @Test
    void workdayLocalePrefixDoesNotCreateASecondJob() {
        assertThat(key("https://dsvgruppe.wd3.myworkdayjobs.com/en-US/careers/job/Stuttgart/Java_R-123"))
                .isEqualTo(key("https://dsvgruppe.wd3.myworkdayjobs.com/careers/job/Stuttgart/Java_R-123"));
    }

    @Test
    void atsTypeAndTenantAreExtracted() {
        ParsedJobUrl parsed = canonicalizer.parse("https://egym.jobs.personio.de/job/1234567");
        assertThat(parsed.type()).isEqualTo(SourceType.PERSONIO);
        assertThat(parsed.tenant()).isEqualTo("egym");
        assertThat(parsed.externalId()).isEqualTo("1234567");
        assertThat(parsed.isKnownAts()).isTrue();
    }

    @Test
    void unknownHostsKeepTheirUrlButLoseTrackingNoise() {
        assertThat(key("https://www.Example.com/careers/senior-java/?utm_source=x&ref=y#top"))
                .isEqualTo("https://example.com/careers/senior-java");
    }

    @Test
    void differentJobsDoNotCollide() {
        assertThat(key("https://boards.greenhouse.io/parloa/jobs/1"))
                .isNotEqualTo(key("https://boards.greenhouse.io/parloa/jobs/2"));
        assertThat(key("https://boards.greenhouse.io/acme/jobs/1"))
                .isNotEqualTo(key("https://boards.greenhouse.io/parloa/jobs/1"));
    }

    private String key(String url) {
        return canonicalizer.parse(url).canonicalUrl();
    }
}
