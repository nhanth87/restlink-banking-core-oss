-- N–N safe inbox drain: claim lease timestamp + reclaim stale CLAIMED rows.
ALTER TABLE provider_event_inbox ADD COLUMN IF NOT EXISTS claimed_ms BIGINT;
