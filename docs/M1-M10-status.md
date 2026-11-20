# M1–M10 status

Phase numbering (0–6) lives in the [architecture overview](design/00-architecture-overview.md). This list is the **P1 production-readiness must-fix list** (2026-08-15 review): auth, prove-artifact, cluster, Temporal fail-open, admin key, Fineract clientId, trust-all TLS, HTMX admin, KYC gate, API audit.

Wallet ledger, Temporal saga, Fineract live probe, and profile/CMP Postgres residency were **already implemented**; this work adds tests where they were thin and does **not** claim a host redeploy.

| # | Milestone | Status | Notes |
|---|-----------|--------|--------|
| **M1** | Auth on public APIs (`/customers`, `/wallet`) | **done** | `X-Restlink-Api-Key` (`RESTLINK_API_KEY`); `change-me` rejected; admin key is superuser. Payments already had admin auth. |
| **M2** | Prove-the-artifact / dist packaging | **done in-tree; host prove blocked** | `app/html/` added; `package-dist.sh` copies repo-root UI + JDK 25 gate + `.new` configs. Live `rsync`/`:8090` prove needs a host — not claimed. |
| **M3** | Cluster `DIST_SYNC` (N≥2) + orphan `adopt` CAS | **done** | Lab default remains `LOCAL` (single-node) with boot warning. `PendingRequestStore.adopt` is `replace` CAS (`putIfAbsent`-style) so two survivors cannot both adopt non-money orphans. Money orphans still skipped (Temporal owns replay). |
| **M4** | `RESTLINK_TEMPORAL_FAIL_OPEN=false` | **done** | Default fail-closed at boot. Lab may set `true` for rolling restarts. |
| **M5** | `RESTLINK_ADMIN_KEY` not `change-me` | **already-existed; hardened** | Rejects unset/`change-me`; now also min length 12. Boot logs error until configured. |
| **M6** | Fineract `OpenAccount` numeric `clientId` | **done** | Lookup `GET /clients?externalId=` then JSON number PK. Never sends the KYC string as `clientId`. |
| **M7** | Guard `trustAllContext()` | **done** | Double gate: `insecure-tls` **and** `allow-insecure-tls-lab`. Either false ⇒ constructor throws; prod defaults both false. |
| **M8** | Admin HTMX UI | **done** | `GET /admin` + `GET /admin/partial/core-status` from disk `app/html/` (`{{TOKEN}}`). Shows moneyReady, coreLive, saga counts. |
| **M9** | KYC minimum viable | **done** | Profile `kycStatus` default `PENDING`. Cash-in allowed; cash-out/transfer **403** until admin `APPROVED`. Document upload is metadata stub only. |
| **M10** | API-level audit | **done** | Mutating calls → `api_audit` (timestamp, tenant, actor fingerprint, method, path, IP, status). No body, no raw keys, PAN redacted. |

## Tests

Unit coverage (happy path + key negatives) lives next to the code: `ApiAuthTest`, `AdminAuthTest`, `ApiAuditTest`, `KycGateTest`, `CustomerDirectoryTest` (KYC persist), `FineractClientIdsTest`, `FineractTlsTest`, `PendingRequestStoreAdoptTest`, `RestlinkClusterViewBridgeTest`, `ClusterModePolicyTest`, `AdminPageRendererTest`, `PackageDistScriptTest`, `TemporalFailOpenDefaultTest`, plus existing wallet/Temporal tests.

## Not in this slice (P2)

ra-temenos OFS ops, Camel ISO/mobile-money hub, OAuth2 open-banking gateway, Fineract `capture()` for cross-provider distributed transfer, live host package→rsync→prove.
