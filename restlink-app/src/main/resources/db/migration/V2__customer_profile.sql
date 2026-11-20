-- Per-customer profile + CMP. No TTL: rows live until explicitly deleted.
-- Heap is a cache; this table is the source of truth across restarts.

CREATE TABLE IF NOT EXISTS customer_profile (
    profile_name      VARCHAR(120) PRIMARY KEY,
    table_name        VARCHAR(40)  NOT NULL DEFAULT 'Customer',
    tenant_id         VARCHAR(40)  NOT NULL,
    customer_id       VARCHAR(80)  NOT NULL,
    first_name        VARCHAR(80),
    last_name         VARCHAR(80),
    mobile_no         VARCHAR(32),
    kyc_id            VARCHAR(80),
    provider          VARCHAR(32),
    default_currency  VARCHAR(3),
    status            VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    fields            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_ms        BIGINT       NOT NULL,
    updated_ms        BIGINT       NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_customer_profile_tenant_cust
    ON customer_profile (tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS ix_customer_profile_mobile
    ON customer_profile (tenant_id, mobile_no);

CREATE TABLE IF NOT EXISTS customer_cmp (
    entity_id         VARCHAR(120) PRIMARY KEY,
    tenant_id         VARCHAR(40)  NOT NULL,
    customer_id       VARCHAR(80)  NOT NULL,
    last_request_id   VARCHAR(80),
    last_account_id   VARCHAR(40),
    last_txn_id       VARCHAR(40),
    last_balance      VARCHAR(40),
    fields            JSONB        NOT NULL DEFAULT '{}'::jsonb,
    updated_ms        BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_customer_cmp_tenant ON customer_cmp (tenant_id);
