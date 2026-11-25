package com.plutus.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class FuzzyNameMatcherTest {

    private final FuzzyNameMatcher matcher = new FuzzyNameMatcher();

    @ParameterizedTest(name = "\"{0}\" vs \"{1}\" in [{2}, {3}]")
    @CsvSource(delimiter = '|', value = {
            // exact after normalisation
            "RAVI KUMAR             | Ravi Kumar               | 1.00 | 1.00",
            // reordered tokens
            "KUMAR RAVI             | Ravi Kumar               | 1.00 | 1.00",
            // honorifics
            "Mr. Ravi Kumar         | RAVI KUMAR               | 1.00 | 1.00",
            "SMT ANITA DESAI        | Anita Desai              | 1.00 | 1.00",
            "Shri Suresh            | Suresh                   | 1.00 | 1.00",
            "M/S Acme Traders Pvt Ltd | ACME TRADERS           | 1.00 | 1.00",
            // company suffixes and &
            "SHARMA AND SONS        | Sharma & Sons            | 1.00 | 1.00",
            "ACME TRADERS PVT. LTD. | Acme Traders Private Limited | 1.00 | 1.00",
            // one-letter typo
            "PRIYA SHARMAA          | Priya Sharma             | 0.95 | 0.99",
            "FATIMA BOUTIQE         | Fatima Boutique          | 0.95 | 0.99",
            // initials: real but weaker evidence
            "R. KUMAR               | Ravi Kumar               | 0.87 | 0.88",
            "KUMAR R                | Ravi Kumar               | 0.87 | 0.88",
            "R K                    | Ravi Kumar               | 0.74 | 0.76",
            // extra unmatched tokens lower the score
            "RAVI KUMAR SHARMA      | Ravi Kumar               | 0.66 | 0.67",
            "MEENA S                | Meena Textiles           | 0.40 | 0.50",
            // clearly different
            "GLOBAL EXPORTS         | Ravi Kumar               | 0.00 | 0.49",
            "MEENA TEXTILES         | Fatima Boutique          | 0.00 | 0.49",
            "AMAZON SELLER SERVICES | Lakshmi Caterers         | 0.00 | 0.49",
            "SURESH ELECTRICALS     | Suresh Kumar             | 0.40 | 0.60",
    })
    void scoresNames(String payer, String customer, double min, double max) {
        double score = matcher.similarity(payer, customer);
        assertThat(score).isBetween(min, max);
        assertThat(matcher.similarity(customer, payer)).isCloseTo(score, within(1e-9)); // symmetric
    }

    @Test
    void handleOnlyEvidence() {
        double score = matcher.similarityWithHandle(null, "priya.s@oksbi", "Priya Sharma");
        assertThat(score).isBetween(0.70, 0.90); // "looks similar", not "same party"
        assertThat(matcher.evidence(null, "priya.s@oksbi", "Priya Sharma"))
                .isEqualTo("UPI handle priya.s resembles Priya Sharma");
    }

    @Test
    void fullNameHandleIsStrongButWeightedBelowName() {
        assertThat(matcher.similarityWithHandle(null, "priya.sharma@oksbi", "Priya Sharma")).isCloseTo(0.90, within(1e-9));
        assertThat(matcher.similarityWithHandle(null, "lakshmicaterers@ybl", "Lakshmi Caterers")).isCloseTo(0.90, within(1e-9));
    }

    @Test
    void handleNeverLowersAGoodName() {
        assertThat(matcher.similarityWithHandle("RAVI KUMAR", "9876543210@paytm", "Ravi Kumar")).isEqualTo(1.0);
        assertThat(matcher.similarityWithHandle("R. KUMAR", "rkumar@okicici", "Ravi Kumar")).isCloseTo(0.875, within(1e-9));
    }

    @Test
    void numericHandleCarriesNoInformation() {
        assertThat(matcher.similarityWithHandle(null, "9876543210@paytm", "Ravi Kumar")).isEqualTo(NameMatcher.NO_INFO);
    }

    @Test
    void nullAndBlankMeanNoInformation() {
        assertThat(matcher.similarity(null, "Ravi Kumar")).isEqualTo(NameMatcher.NO_INFO);
        assertThat(matcher.similarity("Ravi Kumar", null)).isEqualTo(NameMatcher.NO_INFO);
        assertThat(matcher.similarity("  ", "Ravi Kumar")).isEqualTo(NameMatcher.NO_INFO);
        assertThat(matcher.similarity("Mr.", "Ravi Kumar")).isEqualTo(NameMatcher.NO_INFO); // only an honorific
        assertThat(matcher.similarityWithHandle(null, null, "Ravi Kumar")).isEqualTo(NameMatcher.NO_INFO);
        assertThat(matcher.similarityWithHandle(null, " ", "Ravi Kumar")).isEqualTo(NameMatcher.NO_INFO);
    }

    @Test
    void evidenceDescribesNonExactPairs() {
        assertThat(matcher.evidence("R. KUMAR", null, "Ravi Kumar")).isEqualTo("initial R matches RAVI");
        assertThat(matcher.evidence("PRIYA SHARMAA", null, "Priya Sharma")).isEqualTo("SHARMAA is close to SHARMA");
        assertThat(matcher.evidence("RAVI KUMAR", null, "Ravi Kumar")).isEmpty();
    }
}
