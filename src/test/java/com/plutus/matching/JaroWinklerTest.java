package com.plutus.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JaroWinklerTest {

    @ParameterizedTest(name = "{0} / {1} = {2}")
    @CsvSource({
            "MARTHA, MARHTA, 0.961",
            "DIXON, DICKSONX, 0.813",
            "DWAYNE, DUANE, 0.840",
            "SHARMA, SHARMAA, 0.971",
            "CRATE, TRACE, 0.733",
    })
    void knownValues(String a, String b, double expected) {
        assertThat(JaroWinkler.similarity(a, b)).isCloseTo(expected, within(0.001));
        assertThat(JaroWinkler.similarity(b, a)).isCloseTo(expected, within(0.001)); // symmetric
    }

    @Test
    void identicalAndEmptyCases() {
        assertThat(JaroWinkler.similarity("RAVI", "RAVI")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("", "")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("RAVI", "")).isZero();
        assertThat(JaroWinkler.similarity("", "RAVI")).isZero();
        assertThat(JaroWinkler.similarity(null, "RAVI")).isZero();
        assertThat(JaroWinkler.similarity(null, null)).isEqualTo(1.0);
    }

    @Test
    void nothingInCommonIsZero() {
        assertThat(JaroWinkler.similarity("ABC", "XYZ")).isZero();
    }

    @Test
    void sharedPrefixIsRewarded() {
        double samePrefix = JaroWinkler.similarity("KUMARA", "KUMARI");
        double sameLettersNoPrefix = JaroWinkler.similarity("AKUMAR", "IKUMAR");
        assertThat(samePrefix).isGreaterThan(sameLettersNoPrefix);
    }
}
