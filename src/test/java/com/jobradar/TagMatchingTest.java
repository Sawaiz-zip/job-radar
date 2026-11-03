package com.jobradar;

import com.jobradar.api.JobRepository;
import com.jobradar.api.TagMatcher;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tag matching decides what a user is even shown, so a miss here is invisible.
 *
 * <p>A real JetBrains posting, "Software Developer Java/ Node.JS (Web Development Team)",
 * was matched by no tag except a bare "java" chip: the old filter compared the whole
 * phrase as a substring, and "Java Developer" does not occur in "Developer Java".
 */
class TagMatchingTest {

    /** Tags are matched in Java now (TagMatcher), so this is the real code path. */
    private boolean matches(String title, String tag) {
        return new TagMatcher(List.of(tag)).matches(title);
    }

    @Test
    void anyLitTagIsEnoughAndNoTagsMeansEverything() {
        TagMatcher bar = new TagMatcher(List.of("Kotlin Developer", "Java"));
        assertThat(bar.matches("Senior java engineer (m/w/d)")).isTrue();
        assertThat(bar.matches("Developer Kotlin / Android")).isTrue();
        assertThat(bar.matches("Data Analyst")).isFalse();
        assertThat(new TagMatcher(List.of()).matches("Anything at all")).isTrue();
    }

    @Test
    void umlautsMatchCaseInsensitively() {
        assertThat(matches("SOFTWAREENTWICKLER FÜR BACKEND", "Softwareentwickler für")).isTrue();
    }

    @Test
    void wordOrderDoesNotDecideWhetherAJobIsSeen() {
        String jetbrains = "Software Developer Java/ Node.JS (Web Development Team)";
        assertThat(matches(jetbrains, "Java Developer")).isTrue();
        assertThat(matches(jetbrains, "Software Developer")).isTrue();
        assertThat(matches(jetbrains, "Java")).isTrue();
    }

    @Test
    void everyWordOfTheTagMustBePresent() {
        assertThat(matches("Backend Engineer", "Java Developer")).isFalse();
        assertThat(matches("Data Engineer", "AI Engineer")).isFalse();
        assertThat(matches("AI Engineer", "AI Engineer")).isTrue();
    }

    @Test
    void shortTagsDoNotMatchInsideLongerWords() {
        // The reason for word boundaries: a loose "%ai%" hits "maintainer".
        assertThat(matches("Site Reliability Maintainer", "AI")).isFalse();
        assertThat(matches("Senior Java Engineer", "AI")).isFalse();
        assertThat(matches("AI Engineer", "AI")).isTrue();
    }

    @Test
    void punctuationInATagDoesNotBreakTheRegex() {
        assertThat(JobRegexSafe("C++")).isTrue();
        assertThat(JobRegexSafe("Node.JS")).isTrue();
        assertThat(JobRegexSafe("C#")).isTrue();
        assertThat(matches("Senior C++ Developer", "C++")).isTrue();
        assertThat(matches("Node.JS Engineer", "Node.JS")).isTrue();
    }

    /** The produced pattern must at least compile. */
    private boolean JobRegexSafe(String word) {
        try {
            Pattern.compile(JobRepository.wordRegex(word)
                    .replace("\\m", "\\b").replace("\\M", "\\b"));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
