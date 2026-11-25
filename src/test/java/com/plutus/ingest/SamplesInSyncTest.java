package com.plutus.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** /samples (for humans and curl) and src/test/resources/samples (for tests) must not drift apart. */
class SamplesInSyncTest {

    @ParameterizedTest
    @ValueSource(strings = {"invoices.csv", "bank-statement-hdfc-style.csv"})
    void testCopyMatchesRepoSample(String name) throws IOException {
        byte[] repoCopy = Files.readAllBytes(Path.of("samples", name));
        try (InputStream in = getClass().getResourceAsStream("/samples/" + name)) {
            assertThat(in).isNotNull();
            assertThat(in.readAllBytes()).isEqualTo(repoCopy);
        }
    }
}
