package com.plutus.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @ParameterizedTest(name = "\"{0}\" -> {1} paise")
    @CsvSource(delimiter = '|', value = {
            "1234              | 123400",
            "1234.5            | 123450",
            "1234.50           | 123450",
            "1234.05           | 123405",
            "0.01              | 1",
            "0                 | 0",
            "'1,234.50'        | 123450",
            "'1,234,567.89'    | 123456789",
            "'12,34,567.89'    | 123456789",
            "'1,23,45,67,890'  | 123456789000",
            "'₹1,234.50'       | 123450",
            "'₹ 1,234.50'      | 123450",
            "'Rs. 1,234.50'    | 123450",
            "'Rs 1234.50'      | 123450",
            "'Rs.1234'         | 123400",
            "'rs. 99'          | 9900",
            "'INR 1234.50'     | 123450",
            "'INR1234.50'      | 123450",
            "'inr 1234.50'     | 123450",
            "'  1234.50  '     | 123450",
            "'\t₹1,234.50\n'   | 123450",
    })
    void parsesAcceptedFormats(String input, long expectedPaise) {
        assertThat(Money.parseRupeesToPaise(input)).isEqualTo(expectedPaise);
    }

    @ParameterizedTest(name = "rejects \"{0}\"")
    @NullSource
    @ValueSource(strings = {
            "", "   ",                          // empty
            "1.234", "1234.567", "₹1.001",      // more than two decimals
            "-1", "-1234.50", "₹-5", "Rs. -10", "INR -1", // negatives
            "abc", "12a", "1 234", "₹", "Rs.", "INR", "$100", "USD 100", // garbage / wrong currency
            "1,,234", ",123", "123,", "1,2", "12,34", "1234,567", "1.2.3", ".5", "1234.", "1,234.", // malformed
            "99999999999999999999", "92233720368547758.08", // overflows long paise
    })
    void rejectsInvalidInput(String input) {
        assertThatThrownBy(() -> Money.parseRupeesToPaise(input))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} paise -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "0            | ₹0.00",
            "1            | ₹0.01",
            "50           | ₹0.50",
            "12345        | ₹123.45",
            "100000       | ₹1,000.00",
            "123450       | ₹1,234.50",
            "10000000     | ₹1,00,000.00",
            "12345000     | ₹1,23,450.00",
            "100000000    | ₹10,00,000.00",
            "1000000000   | ₹1,00,00,000.00",
            "12345678900  | ₹12,34,56,789.00",
            "-123450      | -₹1,234.50",
    })
    void formatsWithIndianGrouping(long paise, String expected) {
        assertThat(Money.formatPaise(paise)).isEqualTo(expected);
    }

    @Test
    void formatsExtremeValuesWithoutOverflow() {
        assertThat(Money.formatPaise(Long.MAX_VALUE)).isEqualTo("₹92,23,37,20,36,85,47,758.07");
        assertThat(Money.formatPaise(Long.MIN_VALUE)).isEqualTo("-₹92,23,37,20,36,85,47,758.08");
    }

    @Test
    void roundTrips() {
        long paise = 12345678900L;
        assertThat(Money.parseRupeesToPaise(Money.formatPaise(paise))).isEqualTo(paise);
    }
}
