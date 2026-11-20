-- M9 KYC columns + M10 API audit. No TTL. Never load-all.

ALTER TABLE customer_profile
    ADD COLUMN IF NOT EXISTS kyc_status VARCHAR(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE customer_profile
    ADD COLUMN IF NOT EXISTS kyc_document_type VARCHAR(40);
ALTER TABLE customer_profile
    ADD COLUMN IF NOT EXISTS kyc_document_ref VARCHAR(80);

CREATE INDEX IF NOT EXISTS ix_customer_profile_kyc
    ON customer_profile (tenant_id, kyc_status);

CREATE INDEX IF NOT EXISTS ix_customer_cmp_last_account
    ON customer_cmp (tenant_id, last_account_id);

CREATE TABLE IF NOT EXISTS api_audit (
    id          BIGSERIAL    PRIMARY KEY,
    occurred_ms BIGINT       NOT NULL,
    tenant_id   VARCHAR(40),
    actor_id    VARCHAR(40)  NOT NULL,
    method      VARCHAR(8)   NOT NULL,
    path        VARCHAR(240) NOT NULL,
    client_ip   VARCHAR(64),
    status      INTEGER      NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_api_audit_occurred ON api_audit (occurred_ms);
CREATE INDEX IF NOT EXISTS ix_api_audit_tenant ON api_audit (tenant_id, occurred_ms);
