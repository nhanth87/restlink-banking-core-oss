# Restlink Banking Core HTTP API

**Listen:** `quarkus.http.port=8090` (default). JSON in / JSON out.
**Auth:** all `/customers` and `/wallet` routes need `X-Restlink-Api-Key` (`RESTLINK_API_KEY`; default `change-me` is rejected). Admin key (`X-Restlink-Admin-Key`) is accepted as a superuser on those routes. Money *status* and operator signals still need the admin key. Wallet cash-in/out/transfer also use `Idempotency-Key` (not the admin key).
**Money rule:** Temporal down ⇒ **503**, never a direct ledger post. Same `Idempotency-Key` + different payload ⇒ **409**.

Provider is chosen per tenant (`RESTLINK_CORE_PROVIDER`, optional `RESTLINK_CORE_BINDINGS=et:FINERACT,ke:WALLET_LEDGER`). There is **no wallet fallback** if the bound provider is unregistered.

| Provider | Cash-in / cash-out | P2P transfer |
|----------|--------------------|--------------|
| `WALLET_LEDGER` | yes (native JTA) | yes (one JTA debit+credit) |
| `FINERACT` | yes (`POST_LEG` deposit/withdrawal) | **fail-closed** (no native transfer yet) |
| `TEMENOS` | **fail-closed** (OFS scaffold) | **fail-closed** |

---

## Customers

### `POST /customers` — onboard

Idempotent `CustomerDirectory.ensure`: one profile row + one CMP entity per `tenantId:customerId`. Also submits `CreateCustomer` to the bound core (async).

```http
POST /customers
Content-Type: application/json
X-Restlink-Api-Key: $RESTLINK_API_KEY
```

```json
{
  "tenantId": "et",
  "customerId": "cust-001",
  "firstName": "Ada",
  "lastName": "Lovelace",
  "mobileNo": "0911000001",
  "kycId": "kyc-001",
  "currency": "ETB"
}
```

| Status | Body |
|--------|------|
| **202** | `{ key, profile, cmp, requestId }` |
| **400** | `{ error }` — missing `tenantId` / `customerId` |

Poll core CIF with `GET /wallet/response/{requestId}` (non-money pending). Profile is durable immediately (no TTL).

### `GET /customers/{tenantId}/{customerId}`

On-demand load (never warm-all). **404** if no profile.

```json
{
  "key": "et:cust-001",
  "profile": {
    "tenantId": "et",
    "customerId": "cust-001",
    "firstName": "Ada",
    "lastName": "Lovelace",
    "mobileNo": "0911000001",
    "kycId": "kyc-001",
    "kycStatus": "PENDING",
    "provider": "WALLET_LEDGER",
    "defaultCurrency": "ETB",
    "status": "ACTIVE"
  },
  "cmp": {
    "entityId": "et:cust-001",
    "lastRequestId": "REQ-…",
    "lastAccountId": "ACC-…"
  }
}
```

---

## Wallet (accounts + money)

### `POST /wallet/open` — open account

Ensures the customer, then `OpenAccount` on the bound core (wallet row or Fineract savings create→approve→activate).

```json
{ "tenantId": "et", "customerExternalId": "cust-001", "currency": "ETB" }
```

**202** `{ "requestId": "REQ-…" }`. Poll `GET /wallet/response/{requestId}` for `accountId` in the result.

### `GET /wallet/{tenantId}/{accountId}/balance`

Synchronous enquiry on the bound core (`WALLET_LEDGER` ledger row, or Fineract savings `summary`).
Never posts. Includes Restlink freeze + limits (missing control row = not frozen, unlimited).

```json
{
  "tenantId": "et",
  "accountId": "ACC-…",
  "available": "50.00",
  "ledger": "50.00",
  "currency": "ETB",
  "provider": "WALLET_LEDGER",
  "frozen": false,
  "limits": { "perTxn": null, "daily": null }
}
```

| Status | Meaning |
|--------|---------|
| **200** | Snapshot |
| **404** | Unknown account |
| **501** | Provider has no queryBalance (Temenos scaffold) |
| **503** | Peer/datastore down — do not invent a zero |

### `GET /wallet/{tenantId}/{accountId}/history`

Newest-first Temporal money sagas for this account (`from` or `to`). Query `offset` (default 0), `limit` (default 50, max 200).

```json
{
  "tenantId": "et",
  "accountId": "ACC-…",
  "offset": 0,
  "limit": 50,
  "total": 1,
  "items": [
    {
      "paymentId": "pay/et/…",
      "operation": "CASH_IN",
      "direction": "CREDIT",
      "amount": "50.00",
      "currency": "ETB",
      "state": "COMPLETED",
      "coreTxnId": "TXN-…",
      "createdMs": 1710000000000
    }
  ]
}
```

`direction` is `CREDIT` when this account is `to`, `DEBIT` when it is `from`.

### KYC (cash-out / transfer gate)

Onboard leaves `kycStatus=PENDING`. Cash-in is allowed. Cash-out and transfer require `APPROVED` or **403** `KYC_REQUIRED`.

| Method | Path | Auth | Body |
|--------|------|------|------|
| `POST` | `/customers/{tenant}/{id}/kyc-documents` | API key | `{ "documentType": "NATIONAL_ID", "reference": "doc-1" }` — stub metadata, does not approve |
| `POST` | `/customers/{tenant}/{id}/kyc` | **admin key** | `{ "status": "APPROVED" }` (`PENDING` / `APPROVED` / `REJECTED`) |

### Freeze / limits (admin)

Header `X-Restlink-Admin-Key`. Restlink-owned (`wallet_control`) — works for wallet and Fineract account ids.
**New** money is rejected; **in-flight** Temporal sagas still complete.

| Method | Path | Effect |
|--------|------|--------|
| `POST` | `/wallet/{tenantId}/{accountId}/freeze` | Block cash-in/out/transfer (**423** `ACCOUNT_FROZEN`) |
| `POST` | `/wallet/{tenantId}/{accountId}/unfreeze` | Clear freeze |
| `PUT` | `/wallet/{tenantId}/{accountId}/limits` | `{ "perTxn": "1000.00", "daily": "5000.00" }` — omit/null = unlimited |

`perTxn` applies to every money request. `daily` is UTC-day sum of **COMPLETED** cash-out + transfer-out (spend). Over limit ⇒ **400** `LIMIT_EXCEEDED`.

### Money: cash-in / cash-out / transfer

All three **require** header `Idempotency-Key` (opaque client string; never logged as PAN).  
`paymentId = pay/{tenant}/{sha256(Idempotency-Key)}` — raw key is not stored in Temporal history.

| Method | Path | Body |
|--------|------|------|
| `POST` | `/wallet/cash-in` | `{ tenantId, accountId, amount, currency }` |
| `POST` | `/wallet/cash-out` | `{ tenantId, accountId, amount, currency }` |
| `POST` | `/wallet/transfer` | `{ tenantId, fromAccount, toAccount, amount, currency }` |

`amount` must be a positive decimal; `currency` a 3-letter code; transfer `from` ≠ `to`.

| Status | `result` / `error` | Meaning |
|--------|--------------------|---------|
| **202** | `ACCEPTED` + `paymentId` | Workflow started (or already running) |
| **200** | `REPLAY` + `paymentId`, `state`, `coreTxnId` | Same key + same fingerprint, already terminal |
| **400** | `INVALID_REQUEST` | Validation (missing key, bad amount, …) |
| **400** | `LIMIT_EXCEEDED` | Per-txn or UTC daily spend limit |
| **403** | `KYC_REQUIRED` | Cash-out/transfer while KYC is not APPROVED |
| **409** | `IDEMPOTENCY_CONFLICT` | Same key, different payload |
| **423** | `ACCOUNT_FROZEN` | Freeze on from and/or to |
| **503** | `TEMPORAL_UNAVAILABLE` | Fail-closed — do not retry as a new key |

Poll durable status: `GET /payments/{paymentId}` (admin key).

```bash
curl -sS -X POST http://127.0.0.1:8090/wallet/cash-in \
  -H 'Content-Type: application/json' \
  -H "X-Restlink-Api-Key: $RESTLINK_API_KEY" \
  -H 'Idempotency-Key: cashin-et-cust001-20260813-1' \
  -d '{"tenantId":"et","accountId":"ACC-…","amount":"100.00","currency":"ETB"}'
```

### `GET /wallet/response/{requestId}` — legacy poll

Prefer `GET /payments/{paymentId}` for money. This path first looks up a payment id, else a non-money pending request (`OpenAccount` / `CreateCustomer`).

**404** `{ requestId, state: "UNKNOWN" }`.

---

## Payments (operator)

All routes need header **`X-Restlink-Admin-Key`** = `RESTLINK_ADMIN_KEY`.  
Default `change-me` is rejected (`admin key not configured`). Missing/wrong key ⇒ **401**.

There is **no re-post-money** endpoint.

### `GET /payments/{paymentId}`

```json
{
  "paymentId": "pay/et/…",
  "state": "COMPLETED",
  "coreTxnId": "TXN-…",
  "operation": "CASH_IN",
  "provider": "WALLET_LEDGER",
  "error": "",
  "source": "postgres"
}
```

`source` is `postgres` (durable saga) or `temporal` (live query if the row is missing). **404** if unknown.

Saga states: `RECEIVED` → `VALIDATED` → `EXECUTING` → `COMPLETED`.  
Failure path: `COMPENSATING` → `COMPENSATED` or `FAILED`.  
Stuck: `RECONCILING` / `MANUAL_REVIEW` (operator only).

### `POST /payments/{paymentId}/retry-reconcile`

Signal only. Use when the saga is in `MANUAL_REVIEW` / `RECONCILING` after an ambiguous commit. **202** `{ signaled, paymentId }` or **409** if Temporal cannot signal.

### `POST /payments/{paymentId}/retry-compensation`

Signal only. Use after fixing the ledger out-of-band when compensation failed. Never invent a new `Idempotency-Key`.

---

## Admin

Disk HTML + HTMX: `GET /admin?key=` (or header) and `GET /admin/partial/core-status`. JSON:

### `GET /admin/status`

Header `X-Restlink-Admin-Key`. Peer truth only (`coreLive` = probe, not “RA started”).

```json
{
  "node": "restlink-1",
  "ispnMode": "LOCAL",
  "members": ["restlink-1"],
  "pending": 0,
  "idemClaims": 0,
  "defaultProvider": "WALLET_LEDGER",
  "providers": {
    "WALLET_LEDGER": {
      "coreLive": true,
      "capabilities": ["LOOKUP_BY_IDEMPOTENCY", "NATIVE_ATOMIC_TRANSFER", "POST_LEG", "REVERSE"]
    }
  },
  "profiles": 0,
  "profileDurable": true,
  "profileTtl": "none",
  "profileCacheMax": 10000,
  "profileHeapCached": 0,
  "temporal": {
    "target": "127.0.0.1:7233",
    "namespace": "restlink",
    "temporalLive": true,
    "workerPolling": true,
    "moneyReady": true,
    "lastError": ""
  },
  "paymentSagas": {},
  "inboxPending": 0
}
```

`moneyReady` = Temporal frontend reachable **and** worker polling.

---

## Typical lab flow (wallet)

```bash
# 1) onboard
curl -sS -X POST http://127.0.0.1:8090/customers \
  -H 'Content-Type: application/json' \
  -H "X-Restlink-Api-Key: $RESTLINK_API_KEY" \
  -d '{"tenantId":"et","customerId":"cust-001","firstName":"Ada","lastName":"Lovelace","currency":"ETB"}'

# 2) open wallet — then poll /wallet/response/{requestId} for accountId
curl -sS -X POST http://127.0.0.1:8090/wallet/open \
  -H 'Content-Type: application/json' \
  -H "X-Restlink-Api-Key: $RESTLINK_API_KEY" \
  -d '{"tenantId":"et","customerExternalId":"cust-001","currency":"ETB"}'

# 3) cash-in (Temporal)
curl -sS -X POST http://127.0.0.1:8090/wallet/cash-in \
  -H 'Content-Type: application/json' \
  -H "X-Restlink-Api-Key: $RESTLINK_API_KEY" \
  -H 'Idempotency-Key: demo-cashin-1' \
  -d '{"tenantId":"et","accountId":"ACC-…","amount":"50.00","currency":"ETB"}'

# 4) status (admin)
curl -sS http://127.0.0.1:8090/payments/pay/et/$(printf demo-cashin-1 | sha256sum | awk '{print $1}') \
  -H "X-Restlink-Admin-Key: $RESTLINK_ADMIN_KEY"

# 5) balance + history
curl -sS http://127.0.0.1:8090/wallet/et/ACC-…/balance
curl -sS 'http://127.0.0.1:8090/wallet/et/ACC-…/history?limit=20'
```

Fineract tenant: set `RESTLINK_FINERACT_URL` and `RESTLINK_CORE_BINDINGS=et:FINERACT` (or default provider). `accountId` is the Fineract savings id from open. Transfer on Fineract stays fail-closed until native accounttransfers are proven.

See also: [`design/04-temporal-payment-saga.md`](design/04-temporal-payment-saga.md), [`design/03-fineract-best-practices.md`](design/03-fineract-best-practices.md).
