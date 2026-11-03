package com.jobradar.match;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * How much German a posting actually demands.
 *
 * <p>This class only reads the level; the ceiling a user accepts is
 * {@code jobradar.filters.max-german-level} and is applied in {@link HardFilters}. A German-language ad is
 * a hint, not proof - plenty of German-written postings name English as the working
 * language - so only an explicit requirement decides, and everything else stays UNKNOWN
 * for the model to judge.
 */
@Component
public class GermanRequirement {

    public enum Level {
        NONE(0), A1(1), A2(2), B1(3), B2(4), C1(5), C2(6), NATIVE(7), UNKNOWN(-1);

        public final int rank;

        Level(int rank) {
            this.rank = rank;
        }
    }

    private static final Map<Level, List<Pattern>> PHRASES = Map.of(
            Level.NATIVE, List.of(
                    p("verhandlungssicher\\w*\\s+deutsch"),
                    p("deutsch\\s+(?:auf\\s+)?muttersprach\\w*"),
                    p("muttersprach\\w*\\s+deutsch"),
                    p("native[\\s-]+(?:level\\s+)?german"),
                    p("german\\s+(?:at\\s+)?native")),
            Level.C2, List.of(p("deutsch\\w*\\s*[:(-]?\\s*c2"), p("german\\s*[:(-]?\\s*c2")),
            Level.C1, List.of(
                    p("deutsch\\w*\\s*[:(-]?\\s*c1"), p("german\\s*[:(-]?\\s*c1"),
                    p("flie(?:ss|ß)end\\w*\\s+deutsch"),
                    p("fluent\\s+(?:in\\s+)?german"),
                    p("exzellente\\s+deutschkenntnisse"),
                    p("hervorragende\\s+deutschkenntnisse")),
            Level.B2, List.of(
                    p("deutsch\\w*\\s*[:(-]?\\s*b2"), p("german\\s*[:(-]?\\s*b2"),
                    p("sehr\\s+gute\\s+deutschkenntnisse"),
                    p("gute\\s+deutschkenntnisse"),
                    p("gute\\s+kenntnisse\\s+der\\s+deutschen\\s+sprache"),
                    p("business[\\s-]+(?:level\\s+)?german"),
                    p("professional\\s+(?:working\\s+)?proficiency\\s+in\\s+german")),
            Level.B1, List.of(
                    p("deutsch\\w*\\s*[:(-]?\\s*b1"), p("german\\s*[:(-]?\\s*b1"),
                    p("conversational\\s+german"),
                    p("solide\\s+deutschkenntnisse")),
            Level.A2, List.of(
                    p("deutsch\\w*\\s*[:(-]?\\s*a2"), p("german\\s*[:(-]?\\s*a2"),
                    p("grundkenntnisse\\s+(?:in\\s+)?deutsch"),
                    p("deutsch\\w*\\s+grundkenntnisse"),
                    p("basic\\s+german")),
            Level.NONE, List.of(
                    p("no\\s+german\\s+(?:is\\s+)?(?:required|needed|necessary)"),
                    p("german\\s+(?:is\\s+)?not\\s+required"),
                    p("keine\\s+deutschkenntnisse\\s+erforderlich"),
                    p("english\\s+is\\s+(?:our|the)\\s+(?:only\\s+)?(?:company|working|office)\\s+language"),
                    p("we\\s+work\\s+(?:fully\\s+)?in\\s+english")));

    private static Pattern p(String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    /** The highest level the posting explicitly asks for. */
    public Level detect(String text) {
        if (text == null || text.isBlank()) return Level.UNKNOWN;
        String t = text.toLowerCase(Locale.ROOT);

        Level highest = Level.UNKNOWN;
        for (Map.Entry<Level, List<Pattern>> entry : PHRASES.entrySet()) {
            for (Pattern pattern : entry.getValue()) {
                if (pattern.matcher(t).find()) {
                    if (highest == Level.UNKNOWN || entry.getKey().rank > highest.rank) {
                        highest = entry.getKey();
                    }
                    break;
                }
            }
        }
        return highest;
    }
}
