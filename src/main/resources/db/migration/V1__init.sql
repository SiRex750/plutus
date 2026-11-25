-- invoices: what the merchant has billed. One row per invoice; allocated_paise tracks how much of it
-- confirmed allocations have paid so far (denormalised for fast "what's still open?" queries).
CREATE TABLE invoices (
    id              BIGSERIAL    PRIMARY KEY,
    invoice_number  VARCHAR(64)  NOT NULL UNIQUE,
    customer_name   VARCHAR(200) NOT NULL,
    amount_paise    BIGINT       NOT NULL CHECK (amount_paise > 0),
    allocated_paise BIGINT       NOT NULL DEFAULT 0
                    CONSTRAINT ck_invoices_allocated_within_amount
                    CHECK (allocated_paise >= 0 AND allocated_paise <= amount_paise),
    status          VARCHAR(20)  NOT NULL, -- UNPAID, PARTIALLY_PAID, PAID
    issue_date      DATE         NOT NULL,
    due_date        DATE,
    version         BIGINT       NOT NULL DEFAULT 0, -- optimistic locking
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- payments: money actually received, one row per statement line (bank/UPI/PayPal/...).
-- (source, external_ref) is unique so re-uploading the same statement is idempotent.
CREATE TABLE payments (
    id              BIGSERIAL    PRIMARY KEY,
    source          VARCHAR(20)  NOT NULL, -- UPI, BANK_TRANSFER, PAYPAL, CARD, CASH
    external_ref    VARCHAR(100) NOT NULL, -- UTR / PayPal transaction id / bank ref
    payer_name      VARCHAR(200),
    payer_handle    VARCHAR(200),          -- UPI VPA or PayPal email
    amount_paise    BIGINT       NOT NULL CHECK (amount_paise > 0),
    allocated_paise BIGINT       NOT NULL DEFAULT 0
                    CONSTRAINT ck_payments_allocated_within_amount
                    CHECK (allocated_paise >= 0 AND allocated_paise <= amount_paise),
    received_at     TIMESTAMPTZ  NOT NULL,
    narration       TEXT,                  -- raw statement line, kept for audit
    reference_hint  VARCHAR(100),          -- invoice reference parsed from the narration, if any
    status          VARCHAR(20)  NOT NULL, -- UNMATCHED, PARTIALLY_ALLOCATED, ALLOCATED
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_payments_source_external_ref UNIQUE (source, external_ref)
);

-- allocations: the many-to-many link between payments and invoices. One row means
-- "this much of this payment pays this invoice", with how and how confidently it was matched.
-- Supports split payments (one payment -> many invoices) and instalments (many payments -> one invoice).
CREATE TABLE allocations (
    id           BIGSERIAL    PRIMARY KEY,
    payment_id   BIGINT       NOT NULL REFERENCES payments (id),
    invoice_id   BIGINT       NOT NULL REFERENCES invoices (id),
    amount_paise BIGINT       NOT NULL CHECK (amount_paise > 0),
    match_type   VARCHAR(20)  NOT NULL, -- REFERENCE, EXACT_AMOUNT, FUZZY, SPLIT, MANUAL
    confidence   NUMERIC(4,3) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    status       VARCHAR(20)  NOT NULL, -- SUGGESTED, CONFIRMED, REJECTED
    reason       TEXT,                  -- human-readable explanation of why this match was proposed
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at   TIMESTAMPTZ
);

-- At most one live (non-rejected) allocation per payment/invoice pair; rejected ones may be re-proposed.
CREATE UNIQUE INDEX uq_allocations_payment_invoice_live
    ON allocations (payment_id, invoice_id)
    WHERE status <> 'REJECTED';

CREATE INDEX idx_invoices_status ON invoices (status);
CREATE INDEX idx_payments_status ON payments (status);
CREATE INDEX idx_allocations_status ON allocations (status);
CREATE INDEX idx_allocations_invoice_id ON allocations (invoice_id);
