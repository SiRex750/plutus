package com.plutus.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.plutus.AbstractIntegrationTest;

class ImportControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void uploadsInvoices() throws Exception {
        mvc.perform(multipart("/api/imports/invoices").file(sample("invoices.csv")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.fileName").value("invoices.csv"))
                .andExpect(jsonPath("$.totalRows").value(15))
                .andExpect(jsonPath("$.inserted").value(15))
                .andExpect(jsonPath("$.duplicates").value(0))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void uploadsBankStatement() throws Exception {
        mvc.perform(multipart("/api/imports/bank-statement").file(sample("bank-statement-hdfc-style.csv")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(25))
                .andExpect(jsonPath("$.inserted").value(16))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andExpect(jsonPath("$.skipped").value(7))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].lineNumber").value(22));
    }

    @Test
    void emptyFileIsProblemDetail400() throws Exception {
        for (String endpoint : new String[] {"/api/imports/invoices", "/api/imports/bank-statement"}) {
            mvc.perform(multipart(endpoint).file(new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0])))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.title").value("CSV file rejected"))
                    .andExpect(jsonPath("$.reason").value("EMPTY_FILE"));
        }
    }

    @Test
    void csvWithoutRecognisableHeaderIsProblemDetail400() throws Exception {
        byte[] csv = "name,value\nfoo,1\nbar,2\n".getBytes(StandardCharsets.UTF_8);
        for (String endpoint : new String[] {"/api/imports/invoices", "/api/imports/bank-statement"}) {
            mvc.perform(multipart(endpoint).file(new MockMultipartFile("file", "junk.csv", "text/csv", csv)))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.reason").value("HEADER_NOT_FOUND"))
                    .andExpect(jsonPath("$.detail").exists());
        }
    }

    @Test
    void unreadableCsvIsProblemDetail400() throws Exception {
        byte[] csv = "Date,Narration,Credit\n25/11/2026,\"never closed,10\n".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/imports/bank-statement").file(new MockMultipartFile("file", "bad.csv", "text/csv", csv)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("UNREADABLE"));
    }

    @Test
    void missingFilePartIsProblemDetail400() throws Exception {
        mvc.perform(multipart("/api/imports/invoices"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private MockMultipartFile sample(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/samples/" + name)) {
            return new MockMultipartFile("file", name, "text/csv", in);
        }
    }
}
