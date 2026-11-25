package com.plutus.api;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.plutus.ingest.BankStatementImportService;
import com.plutus.ingest.ImportResult;
import com.plutus.ingest.InvoiceImportService;

/** CSV uploads. Both endpoints are idempotent: re-uploading a file inserts nothing new. */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final InvoiceImportService invoiceImport;
    private final BankStatementImportService statementImport;

    public ImportController(InvoiceImportService invoiceImport, BankStatementImportService statementImport) {
        this.invoiceImport = invoiceImport;
        this.statementImport = statementImport;
    }

    @PostMapping(path = "/invoices", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importInvoices(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return invoiceImport.importCsv(in, file.getOriginalFilename());
        }
    }

    @PostMapping(path = "/bank-statement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResult importBankStatement(@RequestParam("file") MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream()) {
            return statementImport.importCsv(in, file.getOriginalFilename());
        }
    }
}
