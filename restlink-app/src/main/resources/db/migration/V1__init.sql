-- Restlink-core Phase 1 — native wallet ledger + durable idempotency/correlation journal.

CREATE TABLE IF NOT EXISTS wallet_account (
    account_id            VARCHAR(40)  PRIMARY KEY,
    tenant_id             VARCHAR(40)  NOT NULL,
    customer_external_id  VARCHAR(80),
    product               VARCHAR(40),
    currency              VARCHAR(3)   NOT NULL,
    balance               NUMERIC(23,4) NOT NULL DEFAULT 0,
    created_ms            BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_wallet_tenant ON wallet_account (tenant_id);

CREATE TABLE IF NOT EXISTS ledger_entry (
    id          BIGSERIAL    PRIMARY KEY,
    core_txn_id VARCHAR(40)  NOT NULL,
    tenant_id   VARCHAR(40)  NOT NULL,
    account_id  VARCHAR(40)  NOT NULL,
    direction   VARCHAR(6)   NOT NULL,
    amount      NUMERIC(23,4) NOT NULL,
    currency    VARCHAR(3)   NOT NULL,
    narrative   VARCHAR(140),
    created_ms  BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_ledger_txn ON ledger_entry (core_txn_id);
CREATE INDEX IF NOT EXISTS ix_ledger_account ON ledger_entry (account_id);

-- Durable exactly-once truth: (tenant, idempotency_key) unique => no double-post survives restarts.
CREATE TABLE IF NOT EXISTS txn_journal (
    core_txn_id     VARCHAR(40)  PRIMARY KEY,
    tenant_id       VARCHAR(40)  NOT NULL,
    request_id      VARCHAR(80),
    idempotency_key VARCHAR(120),
    account_id      VARCHAR(40),
    state           VARCHAR(16)  NOT NULL,
    owner_node      VARCHAR(60),
    created_ms      BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_txn_idem ON txn_journal (tenant_id, idempotency_key);
