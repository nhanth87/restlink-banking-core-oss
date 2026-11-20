# ra-fineract — Apache Fineract best practices (applied)

**Date:** 2026-08-11. Sources: Fineract community wiki *Idempotency – API Retry Fault Tolerance*,
platform docs (`fineract.apache.org/docs/stable`), API overview, and `openMF/fineract` source.
Applied to `ra-fineract` for P0 correctness.

## What we adopted (and why it matters for money)

| # | Best practice | Where applied | P0 reason |
|---|---------------|---------------|-----------|
| 1 | **`Idempotency-Key` header on POST/PUT/PATCH only** (never GET/DELETE). Same key on retry ⇒ Fineract returns the cached response, no re-execution. | `FineractRestClient.postIdempotent`; used for client create, savings open, deposit/withdrawal | No double-debit / double-credit on retry — the core banking rule |
| 2 | **Stable key from a business seed**, normalised to UUID layout (Fineract validates the format). | `FineractRestClient.idempotencyKey(seed)` = `UUID.nameUUIDFromBytes(seed)` | A retry of the *same* logical op reuses the *same* key deterministically |
| 3 | **`dateFormat` + `locale` in every write body** (dates *and* numbers) — omission is a frequent 400. | `FineractResourceAdaptor.stamp()` on every POST | Requests actually succeed; fewer avoidable failures |
| 4 | **Savings lifecycle**: create application → `?command=approve` → `?command=activate` before it can transact. | `handleOpenAccount` (3 steps) | Accounts are usable, not stuck in "submitted" |
| 5 | **Deposit/withdrawal** via `/savingsaccounts/{id}/transactions?command=deposit|withdrawal`. | `handlePostTransaction` | Correct money endpoints |
| 6 | **`externalId`** carries our idempotencyKey (≤100 chars) for traceability + secondary dedupe. | client + transaction bodies | Reconciliation & audit across systems |
| 7 | **Business (4xx) vs transport (I/O, timeout, 5xx) separation.** Only peer-down flips `coreLive`. | `HttpResult.isPeerDown()` / `failByStatus` | Link-truth: a validation 400 must NOT mark Fineract "down" |
| 8 | **Real health probe** for `coreLive` (`/actuator/health`), never RA-start. | `FineractRestClient.probe()` | House link-truth law |
| 9 | **`Fineract-Platform-TenantId` header + Basic auth.** | `FineractRestClient.base()` | Multi-tenancy |
| 10 | **Dev TLS trust toggle** (Fineract images ship self-signed). Off by default. | `insecureTls` | Lab connectivity without weakening prod |

## Interaction with the platform's N–N HA

Two idempotency layers, defence in depth:

1. **restlink N–N guard** (`IdempotencyGuard` + `txn_journal` unique index) — stops a duplicate ever
   reaching a provider, cluster-wide.
2. **Fineract `Idempotency-Key`** — even if a command *is* re-sent to Fineract (RA-level retry after a
   timeout where the first attempt actually committed), Fineract returns the original result.

So a timeout-then-retry can never double-post: the RA reuses the same key (#1/#2 above), and Fineract
de-duplicates. On mid-transaction node death, `RestlinkClusterViewBridge` can safely re-drive by the same
key because the effect is idempotent end-to-end.

## Not in P0 (Phase 2 follow-ups)

- **Batch API** (`/batches`) with per-item `Idempotency-Key` for atomic multi-leg (e.g. transfer as one
  server-side batch) — currently P2P is two legs guarded by a shared idempotency root.
- **OAuth2** bearer auth (Fineract supports it) — P0 uses Basic.
- **Command status polling** (`UNDER_PROCESSING`/`ERROR`, `m_portfolio_command_source`) for async maker-checker.
- **Per-tenant** office/product/currency mapping via profile (P0 uses config defaults).
- **Connection pool tuning / mTLS** for high throughput.
