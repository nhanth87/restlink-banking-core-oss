-- Payment saga durable truth (Temporal orchestrates; Postgres fences double-charge).
-- Never store raw client Idempotency-Key — only SHA-256 hash.

CREATE TABLE IF NOT EXISTS payment_saga (
    payment_id            VARCHAR(160) PRIMARY KEY,
    tenant_id             VARCHAR(40)  NOT NULL,
    idempotency_key_hash  VARCHAR(64)  NOT NULL,
    request_fingerprint   VARCHAR(64)  NOT NULL,
    operation             VARCHAR(16)  NOT NULL,
    from_account_id       VARCHAR(40),
    to_account_id         VARCHAR(40),
    amount                NUMERIC(23,4) NOT NULL,
    currency              VARCHAR(3)   NOT NULL,
    provider              VARCHAR(24)  NOT NULL,
    state                 VARCHAR(24)  NOT NULL,
    core_txn_id           VARCHAR(40),
    workflow_run_id       VARCHAR(80),
    error_message         VARCHAR(400),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_ms            BIGINT       NOT NULL,
    updated_ms            BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_saga_idem
    ON payment_saga (tenant_id, idempotency_key_hash);
CREATE INDEX IF NOT EXISTS ix_payment_saga_state ON payment_saga (state);
CREATE INDEX IF NOT EXISTS ix_payment_saga_tenant ON payment_saga (tenant_id);

CREATE TABLE IF NOT EXISTS payment_step (
    id                    BIGSERIAL    PRIMARY KEY,
    payment_id            VARCHAR(160) NOT NULL REFERENCES payment_saga(payment_id),
    tenant_id             VARCHAR(40)  NOT NULL,
    provider              VARCHAR(24)  NOT NULL,
    step_name             VARCHAR(24)  NOT NULL,
    step_key              VARCHAR(200) NOT NULL,
    state                 VARCHAR(24)  NOT NULL,
    core_txn_id           VARCHAR(40),
    provider_ref          VARCHAR(80),
    attempts              INT          NOT NULL DEFAULT 0,
    compensation_state    VARCHAR(24),
    error_message         VARCHAR(400),
    created_ms            BIGINT       NOT NULL,
    updated_ms            BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_step_key
    ON payment_step (tenant_id, provider, step_key);
CREATE INDEX IF NOT EXISTS ix_payment_step_payment ON payment_step (payment_id);

CREATE TABLE IF NOT EXISTS provider_event_inbox (
    event_id              VARCHAR(120) PRIMARY KEY,
    payment_id            VARCHAR(160),
    request_id            VARCHAR(80),
    step_key              VARCHAR(200),
    provider              VARCHAR(24)  NOT NULL,
    payload_json          TEXT         NOT NULL,
    delivery_state        VARCHAR(16)  NOT NULL,
    attempts              INT          NOT NULL DEFAULT 0,
    created_ms            BIGINT       NOT NULL,
    delivered_ms          BIGINT
);
CREATE INDEX IF NOT EXISTS ix_inbox_delivery ON provider_event_inbox (delivery_state, created_ms);

-- Link journal rows to saga / step for immutable audit.
ALTER TABLE txn_journal ADD COLUMN IF NOT EXISTS payment_id VARCHAR(160);
ALTER TABLE txn_journal ADD COLUMN IF NOT EXISTS step_key VARCHAR(200);
ALTER TABLE txn_journal ADD COLUMN IF NOT EXISTS compensation BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX IF NOT EXISTS ix_txn_journal_payment ON txn_journal (payment_id);
