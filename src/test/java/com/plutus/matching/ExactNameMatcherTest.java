package com.plutus.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ExactNameMatcherTest {

    private final NameMatcher matcher = new ExactNameMatcher();

    @ParameterizedTest(name = "\"{0}\" == \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "Ravi Kumar              | RAVI KUMAR",
            "'  ravi   kumar '       | Ravi Kumar",
            "Acme Traders Pvt Ltd    | ACME TRADERS PVT LTD",
            "Acme Traders Pvt. Ltd.  | ACME TRADERS",
            "Acme Traders Private Limited | Acme Traders Ltd",
            "Sharma & Sons           | SHARMA AND SONS",
            "Sharma and Sons         | Sharma",
            "Krishna & Co.           | KRISHNA AND CO",
            "Joseph D'Souza          | JOSEPH DSOUZA",
            "Bright Ideas LLP        | Bright Ideas",
    })
    void equalAfterNormalisation(String a, String b) {
        assertThat(matcher.similarity(a, b)).isEqualTo(1.0);
        assertThat(matcher.similarity(b, a)).isEqualTo(1.0);
    }

    @ParameterizedTest(name = "\"{0}\" != \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "R. Kumar        | Ravi Kumar",
            "D'Souza Joseph  | Joseph D'Souza",
            "Meena S         | Meena Textiles",
            "Sharma Traders  | Sharma and Sons",
    })
    void differentNames(String a, String b) {
        assertThat(matcher.similarity(a, b)).isZero();
    }

    @Test
    void nullOrBlankMeansNoInformation() {
        assertThat(matcher.similarity(null, "Ravi Kumar")).isZero();
        assertThat(matcher.similarity("Ravi Kumar", null)).isZero();
        assertThat(matcher.similarity(null, null)).isZero();
        assertThat(matcher.similarity("  ", "  ")).isZero();
        assertThat(matcher.similarity("Ltd", "LLP")).isZero(); // nothing left after suffix removal
    }

    @Test
    void policyRejectsInconsistentThresholds() {
        assertThatThrownBy(() -> new MatchingPolicy(0.5, 0.9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchingPolicy(1.1, 0.5)).isInstanceOf(IllegalArgumentException.class);
    }
}
