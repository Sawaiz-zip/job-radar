package com.jobradar.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The tag bar as compiled Java patterns: a title matches when ANY tag matches, and a tag
 * matches when ALL its words appear as whole words, in any order.
 *
 * <p>This used to run inside Postgres as one {@code ~*} per word, OR'd across tags. With
 * every tag lit that is ~250 distinct patterns per row, and Postgres caches only a few
 * dozen compiled regexes per backend, so each row recompiled most of them: 6 seconds for
 * 2,800 rows, measured. Compiled once here, the same match takes milliseconds.
 */
public final class TagMatcher {

    /** Word characters as Postgres {@code \m}/{@code \M} see them: letters, digits, underscore. */
    private static final String W = "[\\p{L}\\p{N}_]";

    private final List<List<Pattern>> tags = new ArrayList<>();

    public TagMatcher(List<String> terms) {
        for (String term : terms == null ? List.<String>of() : terms) {
            List<Pattern> words = new ArrayList<>();
            for (String word : JobRepository.words(term)) {
                words.add(Pattern.compile(wordPattern(word),
                        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            }
            if (!words.isEmpty()) tags.add(words);
        }
    }

    /** True when there is nothing to match against, i.e. every title passes. */
    public boolean isEmpty() {
        return tags.isEmpty();
    }

    public boolean matches(String title) {
        if (tags.isEmpty()) return true;
        if (title == null) return false;
        for (List<Pattern> words : tags) {
            boolean all = true;
            for (Pattern p : words) {
                if (!p.matcher(title).find()) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    /**
     * One word, anchored to word boundaries only where it starts or ends in a word
     * character: "C++" anchored on the right would never match, since "+" is not one.
     */
    static String wordPattern(String word) {
        StringBuilder out = new StringBuilder();
        if (Character.isLetterOrDigit(word.charAt(0))) out.append("(?<!").append(W).append(')');
        out.append(Pattern.quote(word));
        if (Character.isLetterOrDigit(word.charAt(word.length() - 1))) out.append("(?!").append(W).append(')');
        return out.toString();
    }
}
