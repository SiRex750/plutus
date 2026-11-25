package com.plutus.ingest.csv;

import static com.plutus.ingest.csv.ColumnSpec.optional;
import static com.plutus.ingest.csv.ColumnSpec.required;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * The columns a CSV file is expected to have, and which of them identify the header row
 * (the first row, within {@link CsvTableReader#MAX_HEADER_SEARCH_ROWS}, containing all detection columns).
 */
public record TableSpec(String name, List<ColumnSpec> columns, List<String> detectionKeys) {

    public static final String INVOICE_NUMBER = "invoice_number";
    public static final String CUSTOMER_NAME = "customer_name";
    public static final String AMOUNT = "amount";
    public static final String ISSUE_DATE = "issue_date";
    public static final String DUE_DATE = "due_date";

    public static final String DATE = "date";
    public static final String NARRATION = "narration";
    public static final String REF = "ref";
    public static final String CREDIT = "credit";
    public static final String DEBIT = "debit";

    public static final TableSpec INVOICES = new TableSpec("invoices", List.of(
            required(INVOICE_NUMBER, "Invoice No", "Invoice Number", "Inv No", "Invoice #"),
            required(CUSTOMER_NAME, "Customer", "Customer Name", "Client", "Bill To"),
            required(AMOUNT, "Amount", "Total", "Invoice Amount", "Grand Total"),
            required(ISSUE_DATE, "Date", "Invoice Date", "Issue Date"),
            optional(DUE_DATE, "Due Date", "Due")),
            List.of(INVOICE_NUMBER, AMOUNT));

    public static final TableSpec BANK_STATEMENT = new TableSpec("bank statement", List.of(
            required(DATE, "Date", "Txn Date", "Transaction Date", "Value Date"),
            required(NARRATION, "Narration", "Description", "Particulars", "Remarks"),
            optional(REF, "Ref No", "Chq/Ref No", "Chq./Ref.No.", "Reference No", "UTR"),
            required(CREDIT, "Deposit Amt", "Deposit Amt.", "Credit", "Credit Amount", "Deposit"),
            optional(DEBIT, "Withdrawal Amt", "Withdrawal Amt.", "Debit", "Debit Amount")),
            List.of(DATE, NARRATION));

    public TableSpec {
        columns = List.copyOf(columns);
        detectionKeys = List.copyOf(detectionKeys);
    }

    public ColumnSpec column(String key) {
        return columns.stream().filter(c -> c.key().equals(key)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("No column " + key + " in " + name));
    }
}
