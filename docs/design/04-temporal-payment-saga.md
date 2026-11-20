# Restlink Banking Core — Temporal payment saga (exactly-once business effect)

**Date:** 2026-08-12. Complements [`02-nn-ha.md`](02-nn-ha.md).

## Guarantees

| Guarantee | Mechanism |
|-----------|-----------|
| Exactly-once **business** money effect | Durable step idempotency keys in Postgres `txn_journal` / provider native keys. Temporal Activities are **at-least-once**. |
| No double-charge on crash after commit | Activity retry returns prior `coreTxnId` via unique `(tenant, step_key)`. |
| No split-brain P2P | Native wallet: one JTA debit+credit. Cross-provider: reserve→credit→capture Saga with LIFO compensation. |
| Temporal down | API returns **503** — never fall back to direct post. |
| Same Idempotency-Key, different payload | `409 IDEMPOTENCY_CONFLICT` via request fingerprint. |

## Workflow id

`pay/{tenant}/{sha256(idempotencyKey)}` — raw client key never stored in Temporal history.

## State machine

```
RECEIVED → VALIDATED → EXECUTING → COMPLETED
                              ↘ COMPENSATING → COMPENSATED
                              ↘ RECONCILING → (retry or MANUAL_REVIEW)
```

Distributed transfer: `DEBIT_RESERVED → CREDIT_CONFIRMED → DEBIT_CAPTURED`.

## Compensation rules

1. Register compensation **before** forward Activity.
2. Compensations run LIFO, idempotent, no-op if forward never committed.
3. Compensation failure → `MANUAL_REVIEW`; operator signals `retryCompensation` / `retryReconcile` only — **no re-post button**.

## Timeout / retry matrix

| Class | Policy |
|-------|--------|
| Business reject | Non-retryable (`BUSINESS_REJECT`) |
| Transport / transient | Exponential backoff, max 8 attempts |
| Ambiguous commit | `RECONCILING` → lookup-by-step-key; unknown → `MANUAL_REVIEW` |

## Tables

- `payment_saga` — durable payment row (`UNIQUE(tenant_id, idempotency_key_hash)`)
- `payment_step` — per-step state (`UNIQUE(tenant_id, provider, step_key)`)
- `provider_event_inbox` — persist-before-signal

## MANUAL_REVIEW runbook

1. Read `/payments/{id}` and Temporal UI history.
2. Query provider / `txn_journal` by step key.
3. If effect present → signal `retryReconcile` after fixing lookup.
4. If effect absent and safe → signal `retryCompensation`.
5. Never invent a new Idempotency-Key or force a second post.
