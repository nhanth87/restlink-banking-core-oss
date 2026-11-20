# Self-hosted Temporal for Restlink payment saga (lab)

Dedicated Temporal PostgreSQL databases — **never** reuse the Restlink application DB.

## Quick lab

```bash
cd deploy/temporal
docker compose up -d
# wait for temporal:7233
docker compose exec temporal temporal operator namespace create --namespace restlink --retention 72h || true
```

Restlink config:

```properties
restlink.temporal.enabled=true
restlink.temporal.target=127.0.0.1:7233
restlink.temporal.namespace=restlink
```

## Topology

| Component | Role |
|-----------|------|
| `postgresql` | Temporal persistence (`temporal`) + visibility (`temporal_visibility`) |
| `temporal` | Frontend / History / Matching / Worker (dev image) |
| `temporal-ui` | Web UI `:8088` (lab only; change bind in prod) |

## Production notes

- Run replicated Frontend / History / Matching / Worker services (not the all-in-one auto-setup image).
- PostgreSQL HA + backups for both Temporal DBs; schema upgrades via `temporal-sql-tool`.
- mTLS internode + frontend; client certs from env (`RESTLINK_TEMPORAL_*`).
- Namespace retention + archival; Prometheus alerts on schedule-to-start latency and sticky task failures.
- `dist/restlink-app` ships **client/worker only** — Temporal server is a separate mandatory deploy unit for money readiness.

## Money readiness

`/admin/status` → `temporal.moneyReady` is true only when:

1. Temporal frontend answers `GetSystemInfo`, and
2. Restlink workers are polling task queues.

If Temporal is down, cash-in / cash-out / transfer return **503** — no direct ledger fallback.
