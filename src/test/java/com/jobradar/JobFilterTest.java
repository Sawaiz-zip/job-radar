package com.jobradar;

import com.jobradar.api.JobFilter;
import com.jobradar.api.JobRepositoryAccess;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filter bar is built into one WHERE clause. A placeholder without its parameter (or
 * the other way round) fails only for the one combination of filters that triggers it,
 * which is exactly what looked like the list "glitching" now and then.
 */
class JobFilterTest {

    private static JobFilter everything() {
        return new JobFilter("OPEN", "chance", List.of("Java Developer", "C++", ".NET"),
                "spring kafka", "Munich", true, "board", 40, 50, 4, "A2", true, false,
                true, false, "72", 168, 7L, 300);
    }

    private static JobFilter nothing() {
        return new JobFilter(null, null, null, null, null, false, null, null, null, null,
                null, false, true, false, true, null, null, null, 300);
    }

    @Test
    void everyPlaceholderHasExactlyOneParameter() {
        for (JobFilter f : List.of(everything(), nothing())) {
            List<Object> params = new ArrayList<>();
            String sql = JobRepositoryAccess.where(f, params);
            long placeholders = sql.chars().filter(ch -> ch == '?').count();
            assertThat(params).hasSize((int) placeholders);
        }
    }

    @Test
    void noFiltersMeansNoConditions() {
        assertThat(JobRepositoryAccess.where(nothing(), new ArrayList<>())).isEmpty();
    }

    @Test
    void germanCapHidesOnlyWhatIsAboveIt() {
        assertThat(JobRepositoryAccess.germanAbove("A2")).containsExactly("B1", "B2", "C1", "C2", "native");
        assertThat(JobRepositoryAccess.germanAbove("b2")).containsExactly("C1", "C2", "native");
        assertThat(JobRepositoryAccess.germanAbove("")).isEmpty();
        assertThat(JobRepositoryAccess.germanAbove("fluent")).isEmpty();
    }

    @Test
    void eitherSpellingOfACityFindsBoth() {
        assertThat(JobRepositoryAccess.cityVariants("Munich")).contains("münchen", "munich");
        assertThat(JobRepositoryAccess.cityVariants("Köln")).contains("cologne");
        assertThat(JobRepositoryAccess.cityVariants("Berlin")).containsExactly("berlin");
    }

    @Test
    void typedWordsKeepDotNetAndDropSentencePunctuation() {
        assertThat(JobRepositoryAccess.words(".NET developer")).containsExactly(".NET", "developer");
        assertThat(JobRepositoryAccess.words("Node.js.")).containsExactly("Node.js");
        assertThat(JobRepositoryAccess.words("  ")).isEmpty();
    }
}
