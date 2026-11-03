package com.jobradar;

import com.jobradar.match.FilterProperties;
import com.jobradar.match.GermanRequirement;
import com.jobradar.match.HardFilters;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The filters are configuration, so each setting must actually change what is kept. */
class FilterConfigTest {

    private final GermanRequirement german = new GermanRequirement();

    private HardFilters with(FilterProperties props) {
        return new HardFilters(german, props);
    }

    @Test
    void defaultsSetNoCeilingOnYearsOrGerman() {
        HardFilters filters = with(FilterProperties.defaults());
        assertThat(filters.evaluate("Backend Engineer", "10+ years of experience").keep()).isTrue();
        assertThat(filters.evaluate("Backend Engineer", "Verhandlungssichere Deutschkenntnisse").keep()).isTrue();
        // ...but the default title gate is still a software-engineering search.
        assertThat(filters.evaluate("Sales Manager", "").keep()).isFalse();
    }

    @Test
    void studentRolesAreDroppedUnlessSwitchedOn() {
        assertThat(with(FilterProperties.defaults()).evaluate("Werkstudent Softwareentwicklung", "").keep())
                .isFalse();
        HardFilters students = with(new FilterProperties(null, null, null, null, null, null, true, null, null));
        assertThat(students.evaluate("Werkstudent Softwareentwicklung", "").keep()).isTrue();
    }

    @Test
    void seniorTitlesCanBeKept() {
        HardFilters keepSenior = with(new FilterProperties(null, null, null, null, false, null, null, null, null));
        assertThat(keepSenior.evaluate("Staff Platform Engineer", "").keep()).isTrue();
    }

    @Test
    void anotherFieldCanBeSearchedByReplacingTheTitleGate() {
        HardFilters mechanical = with(new FilterProperties(null, null,
                List.of("maschinenbau", "mechanical", "konstrukt"), List.of("sales"),
                null, null, null, false, null));
        assertThat(mechanical.evaluate("Konstrukteur Maschinenbau (m/w/d)", "").keep()).isTrue();
        assertThat(mechanical.evaluate("Mechanical Design Engineer", "").keep()).isTrue();
        assertThat(mechanical.evaluate("Backend Engineer", "").keep()).isFalse();
    }

    @Test
    void boostsRaiseTheRuleScoreAndClampAtTheBounds() {
        HardFilters boosted = with(new FilterProperties(null, null, null, null, null, null, null, null,
                List.of(new FilterProperties.Boost("\\bjava\\b", 30), new FilterProperties.Boost("\\bsenior\\b", -80))));
        int plain = boosted.evaluate("Backend Engineer", "").ruleScore();
        assertThat(boosted.evaluate("Java Backend Engineer", "").ruleScore()).isEqualTo(plain + 30);
        assertThat(boosted.evaluate("Senior Backend Engineer", "").ruleScore()).isZero();
    }
}
