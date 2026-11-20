-- Restlink-owned freeze + limits. Not a Fineract/Temenos table — works for any bound account id.
-- Missing row = ACTIVE, unlimited. In-flight Temporal sagas are not aborted by a freeze.

CREATE TABLE IF NOT EXISTS wallet_control (
    tenant_id      VARCHAR(40)  NOT NULL,
    account_id     VARCHAR(40)  NOT NULL,
    frozen         BOOLEAN      NOT NULL DEFAULT FALSE,
    per_txn_limit  NUMERIC(23,4),
    daily_limit    NUMERIC(23,4),
    updated_ms     BIGINT       NOT NULL,
    PRIMARY KEY (tenant_id, account_id)
);

CREATE INDEX IF NOT EXISTS ix_payment_saga_from ON payment_saga (tenant_id, from_account_id, created_ms);
CREATE INDEX IF NOT EXISTS ix_payment_saga_to   ON payment_saga (tenant_id, to_account_id, created_ms);
