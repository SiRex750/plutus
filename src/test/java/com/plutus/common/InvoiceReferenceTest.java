package com.plutus.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InvoiceReferenceTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "INV-1042      | INV-1042",
            "INV1042       | INV-1042",
            "inv 1042      | INV-1042",
            "INV/1042      | INV-1042",
            "inv#1042      | INV-1042",
            "'  Inv - 1042 '| INV-1042",
            "INV-0007      | INV-0007",
            "BILL-2024     | BILL-2024",
            "'  so-77a '   | SO-77A",
            "INVOICE-5     | INVOICE-5",
    })
    void normalizes(String input, String expected) {
        assertThat(InvoiceReference.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"INV1042, inv 1042", "INV-1042, inv#1042", "INV/1042, Inv1042"})
    void invoiceAndNarrationSpellingsConverge(String onInvoice, String inNarration) {
        assertThat(InvoiceReference.normalize(onInvoice)).isEqualTo(InvoiceReference.normalize(inNarration));
    }

    @org.junit.jupiter.api.Test
    void nullStaysNull() {
        assertThat(InvoiceReference.normalize(null)).isNull();
    }
}
