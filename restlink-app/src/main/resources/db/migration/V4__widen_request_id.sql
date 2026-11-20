-- paymentId is "pay/<tenant>/<64-hex sha256>" and is used as request_id on money legs.
-- VARCHAR(80) overflows for realistic tenant ids → insert failure → stuck payment. Widen to 160.

ALTER TABLE txn_journal          ALTER COLUMN request_id TYPE VARCHAR(160);
ALTER TABLE provider_event_inbox ALTER COLUMN request_id TYPE VARCHAR(160);
