# Monolith Events API

Central domain-event ingest for [Continuum Home](https://continuum-home.vercel.app) and other
personal applications. Java 21 / Spring Boot 3, backed by BigQuery, deployed to Cloud Run behind
`monolith-postbacks.adithyakrishnan.com`.

The service does one job: accept application-history events from a trusted server backend, route
each to its per-app, per-domain BigQuery table, and post a visible confirmation to Discord.

---

## Endpoints

| Method | Path | Auth | Notes |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/events/postback` | **bearer** | Domain-event ingest. Rate limited per IP. Returns `202 Accepted`. |
| `GET`  | `/api/v1/audit/logs` | **bearer** | Read domain-event history, newest first. Confined to the calling credential's app. |
| `GET`  | `/api/v1/reports` | **bearer** | List the admin-authored reports this credential may run. |
| `POST` | `/api/v1/reports/{id}/run` | **bearer** | Run one report; returns `text/csv`. Scoped per credential. |
| `GET`  | `/health`, `/` | none | Liveness. Touches no backend. |
| `GET`  | `/swagger-ui.html` | none | Interactive API docs. |

`/events/*` is retained as an alias for the versioned path.

---

## Developer Documentation & Model Context Protocol (MCP) Server

- **Documentation Website:** [https://monolith.adithyakrishnan.com](https://monolith.adithyakrishnan.com) (Single-page developer portal rendered in warm parchment cream theme).
- **Model Context Protocol (MCP) Server:** `https://monolith.adithyakrishnan.com/api/mcp` (Exposes `query_user_activity`, `query_domain_events`, `get_bigquery_schema`, and `get_system_health` tools for AI agents).
- **App Integration Guide:** Complete step-by-step developer guide in [`APP_INTEGRATION_GUIDE.md`](APP_INTEGRATION_GUIDE.md).

### Ingest

```bash
curl -X POST https://monolith-postbacks.adithyakrishnan.com/api/v1/events/postback \
  -H "Authorization: Bearer $CONTINUUM_API_KEY" -H 'Content-Type: application/json' \
  -d '{"sourceApp":"continuum-home","eventType":"EXPENSE_CREATED","userId":"usr_1","payload":{"amount":42.5,"category":"food"}}'
```

`202 Accepted` means validated, routed, and queued. The BigQuery insert happens off the request
thread, so **the response is not a durability guarantee** — telemetry must never add latency to,
or fail, the flow it observes. An `eventType` outside the allowlist (`DomainEventType`) returns
`400 REJECTED` and stores nothing, rather than silently dropping a client typo.

A ready-to-run request in [`bruno/Domain Events`](bruno/Domain%20Events) — open the `bruno/`
folder in [Bruno](https://www.usebruno.com) and select the `Local` or `Production` environment.

---

## Reading events back

```bash
curl -s -G https://monolith-postbacks.adithyakrishnan.com/api/v1/audit/logs \
  -H "Authorization: Bearer $CONTINUUM_API_KEY" \
  --data-urlencode 'userId=usr_1' --data-urlencode 'domain=expenses' --data-urlencode 'limit=50'
```

```json
{
  "scope": "continuum-home",
  "count": 1,
  "results": [
    { "domain": "expenses", "eventId": "…", "sourceApp": "continuum-home", "userId": "usr_1",
      "eventType": "EXPENSE_CREATED", "action": "CREATE", "entityId": "exp_1", "itemCount": 1,
      "occurredAt": "2026-08-30T12:00:00Z", "receivedAt": "2026-08-30T12:00:01Z",
      "payload": { "amount": 42.5, "category": "food" } }
  ],
  "nextBefore": "2026-08-30T12:00:00Z"
}
```

**A credential can only read its own app.** The `source_app` filter is set server-side from the
authenticated key — a client passing `?sourceApp=` for a different app gets `403`, not another
app's rows. Which credential may read which app is the checked-in registry
[`clients.json`](src/main/resources/clients.json) — no secrets in it, one entry per credential:

```json
{ "clients": [
  { "name": "owner",     "keyProperty": "dashboard.api-key", "readScope": "all" },
  { "name": "continuum", "readScope": "continuum-home" }
] }
```

| `readScope` | Effect |
| :--- | :--- |
| `all` | Reads every app. Also every allow-listed Google identity. |
| a registered `sourceApp` id (e.g. `continuum-home`) | Reads only that app. |
| `none` | Still authenticates for ingest; every read is `403`. |

**No secret is added per app.** The owner's `keyProperty` resolves its key from a named property
(`dashboard.api-key` ← `API_KEY`). Every other client's key is looked up by `name` in one
aggregated secret, `MONOLITH_CLIENT_KEYS` — a `{"name":"token"}` JSON map (or `name=token` CSV).
Onboarding a client is a row here plus a new *version* of that one secret; the count of Secret
Manager secrets never grows. Startup fails loudly on a bad scope, a duplicate name, or a
malformed `MONOLITH_CLIENT_KEYS`.

Filters: `sourceApp` (cross-app credentials only), `userId`, `domain`, `eventType` (all checked
against the same allowlists ingest uses), `from` / `before` (`occurred_at` bounds, ISO-8601 or
epoch millis), `limit`. With no `from`, only the last `AUDIT_QUERY_LOOKBACK_DAYS` days are
scanned. Paginate by passing the previous response's `nextBefore` as `before`. Reads have their
own tighter per-IP and per-credential rate limit (`AUDIT_READ_RATE_LIMIT_PER_MINUTE`) because
each one is a BigQuery scan.

---

## Reports

Admin-authored named queries, run on demand and returned as CSV. The catalog is the checked-in
[`reports.json`](src/main/resources/reports.json) — each entry is a single parameterised
`SELECT`/`WITH`:

```json
{ "id": "activity-summary", "name": "Activity summary by event type",
  "params": [{ "name": "from", "type": "timestamp", "required": true }],
  "sql": "SELECT event_type, COUNT(*) AS events FROM {{all_events}} WHERE source_app = @caller_app AND occurred_at >= @from GROUP BY event_type" }
```

- `@param` values are **bound**, never concatenated. `{{all_events}}` expands to the FQN of the
  `events.all_events` view. `@caller_app`, when present, is the calling credential's bound
  `sourceApp` — one definition serves every client, each seeing only its rows; a cross-app
  credential passes `callerApp` in the run body.
- **Allotment** is per credential in `clients.json` (`"reports": ["activity-summary", …]`, or
  `["*"]`). A cross-app credential may run any report; a scoped one only its list.
- Guards: `SELECT`-only single statement (enforced at startup — a non-`SELECT` or a stray `;`
  fails the boot), `REPORTS_MAX_BYTES_BILLED`, a job timeout, and `REPORTS_MAX_ROWS` (the CSV
  carries `X-Report-Truncated: true` past it). Shares the read rate limit.

```bash
curl -s https://monolith-postbacks.adithyakrishnan.com/api/v1/reports \
  -H "Authorization: Bearer $KEY"

curl -s -X POST https://monolith-postbacks.adithyakrishnan.com/api/v1/reports/activity-summary/run \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"from":"2026-08-01T00:00:00Z"}' -o activity-summary.csv
```

Adding a report is an entry in `reports.json` + a deploy. No arbitrary SQL crosses the wire.

---

## Why this is authenticated

Unlike a browser-facing endpoint, a domain event is only ever emitted by a source app's own
backend, after a write has already committed there. So this one can demand a credential — and
does. Without that, anyone who found the URL could fabricate an expense record in the analytics
store; the whole value of this data is that it's trustworthy. Continuum only sends events when
`MONOLITH_API_KEY` is set on its side; unset means events are skipped rather than sent
unauthenticated.

---

## Configuration

Everything cost-relevant is bound through `AuditProperties` (prefix `audit.`, kept for
continuity with already-deployed config) so it's tunable without a code change.

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `API_KEY` | — | The owner / admin bearer key (`clients.json` client `owner`, `readScope: all`). **Required.** |
| `MONOLITH_CLIENT_KEYS` | — | Every non-owner client key in one value: `{"continuum":"tok",…}` JSON or `continuum=tok,…` CSV. One secret for all apps; adding an app is a new version, not a new secret. |
| `DISCORD_WEBHOOK_URL` | — | Where the "event received" ping goes. Notifications are skipped (not sent unauthenticated) if unset. |
| `GCP_PROJECT_ID` | `portfolio-api-505006` | BigQuery project. |
| `AUDIT_RATE_LIMIT_PER_MINUTE` | `120` | Per-IP *and* per-credential postback budget, per instance. A leaked key replayed from rotating IPs still hits the per-credential ceiling. `0` disables. |
| `AUDIT_GLOBAL_RATE_LIMIT_PER_MINUTE` | `300` | Looser per-IP budget across every endpoint (health, swagger, actuator included), per instance — a backstop in front of the tighter postback-specific budget above. `0` disables. |
| `MONOLITH_CLIENTS_FILE` | `classpath:clients.json` | Location of the credential/read-scope/report-allotment registry. Point at an external file to change it without a rebuild. |
| `MONOLITH_REPORTS_FILE` | `classpath:reports.json` | Location of the report catalog. |
| `REPORTS_MAX_BYTES_BILLED` / `REPORTS_MAX_ROWS` | `200000000` / `50000` | Per-run BigQuery scan cap and row cap for `/reports/{id}/run`. |
| `REPORTS_TIMEOUT_MILLIS` | `30000` | Per-run BigQuery job timeout. |
| `AUDIT_READ_RATE_LIMIT_PER_MINUTE` | `30` | Per-IP *and* per-credential budget for `/audit/logs`, per instance. Lower than ingest — a read is a BigQuery scan. `0` disables. |
| `AUDIT_QUERY_LOOKBACK_DAYS` | `30` | With no `?from`, how far back `/audit/logs` scans. Bounds query cost. |
| `AUDIT_QUERY_DEFAULT_LIMIT` / `AUDIT_QUERY_MAX_LIMIT` | `50` / `200` | Default and hard-capped row count for `/audit/logs`. |
| `AUDIT_QUERY_MAX_BYTES_BILLED` | `100000000` | BigQuery refuses a read estimated to bill above this. `0` removes the cap. |
| `AUDIT_BIGQUERY_ENABLED` | `true` | Escape hatch to disable BigQuery writes (and reads) without a redeploy. |
| `AUDIT_BIGQUERY_DOMAIN_DATASET` | `events` | BigQuery dataset for the per-app domain-event tables. |
| `AUDIT_BIGQUERY_LOCATION` | `US` | Dataset location. Fixed at creation time — see BigQuery setup below. |
| `ALLOWED_EMAIL` | — | Google ID token identity accepted as an alternate credential. No current endpoint needs a human/browser caller — kept as general-purpose auth infrastructure, not because anything here uses it today. |

**No secret has a default in `application.yml`.** A missing `API_KEY` makes the service reject
every authenticated request and log an error at startup — it does not fall back to open access.

---

## BigQuery setup

```bash
./infra/setup-bigquery.sh portfolio-api-505006 US
```

Idempotent, and creates one dataset (`events`):

- **One table per `(app, domain)`** — e.g. `continuum_home_expenses` — all sharing one column
  set: `event_id`, `source_app`, `local_user_id`, `event_type`, `action`, `entity_id`,
  `item_count`, `occurred_at`, `received_at`, `payload`. Partitioned by `occurred_at`, clustered
  by `local_user_id, event_type`. `payload` is a native `JSON` column rather than fixed columns,
  since its shape varies per domain and event type.
- **`all_events`** (view) — every domain table unioned into one queryable stream. Onboarding a
  new domain or app adds one `UNION ALL` branch here, never a new query shape:

```sql
SELECT domain, event_type, occurred_at, payload
FROM `portfolio-api-505006.events.all_events`
WHERE local_user_id = 'usr_1'
ORDER BY occurred_at DESC
```

`eventId` (client-generated) is used as the BigQuery `insertId` on every insert, so a retried or
resent postback lands once instead of twice. Streaming inserts retry once on a transient
connection failure (`BigQueryInserts`) — safe only because of that same `insertId`.

---

## Domain event design

### Callers name an event, never a table

The destination is resolved server-side from `eventType` by `DomainEventType`, which is both
the allowlist and the routing table. `EXPENSE_CREATED` from `continuum-home` lands in
`events.continuum_home_expenses`.

Letting the client name its own table was the obvious alternative and is worse: table
identifiers can't be parameterized the way values can, so a caller-supplied destination becomes
a hand-rolled validation problem — and schema ownership drifts to whoever is calling the
endpoint. Routing here costs one enum entry per event and keeps that decision in this repo.

### Batching

Batch operations emit **one** event carrying `itemCount`, not one per row. A 200-row CSV import
sending 200 postbacks would exhaust `AUDIT_RATE_LIMIT_PER_MINUTE` and add no information.

### Payloads keep structure, not content

Continuum sends amounts, categories, and dates — not expense titles or notes. Free-text personal
content stays in Continuum's own Firestore, encrypted; this store has no use for it.
`PayloadSanitizer` bounds and redacts whatever payload does arrive (entry count, value length,
nesting depth, credential-shaped keys).

---

## Observability — Discord

Every accepted event posts a one-line confirmation to Discord (`DiscordNotifier`) — purely a
visible heartbeat so a deploy can be eyeballed as working in UAT/prod without a BigQuery query.
It is **not** an alert pipeline: no severity, no dedup, no backoff. If it ever gets noisy, mute
it by unsetting `DISCORD_WEBHOOK_URL`, or stop calling it from whichever call site is noisy —
not by adding throttling to the notifier itself.

---

## Cost model

The service sits in the Cloud Run and BigQuery free tiers under normal personal use. The things
that could take it out of them, in order:

1. **Unbounded ingest.** The endpoint requires a bearer key, so this is bounded by whoever holds
   a client key — but `PostbackRateLimitFilter` still caps per-IP and per-credential throughput as
   a backstop against a leaked key or a runaway caller.
2. **Payload size.** `PayloadSanitizer` caps entry count, value length, and nesting depth.
3. **BigQuery streaming inserts.** `insertAll` is used rather than the Storage Write API: at this
   project's volume the difference is pennies, and `insertAll`'s per-row `insertId` gives
   exact-dedup semantics the Storage Write API's default stream doesn't. If ingest volume ever
   grows enough for this line item to matter, that's the thing to revisit.

Cold start is the other lever. The image pre-trains a CDS archive at build time, measured at
roughly 28% off JVM startup (2.48s → 1.82s locally), which matters because `--min-instances=0`
means every idle period ends in a cold start the user waits on.

---

## Local development

```bash
mvn test
```

```bash
API_KEY=local-dev-key mvn spring-boot:run
```

The BigQuery client is a `@Lazy` bean, so the application starts without GCP credentials —
writes are skipped and logged instead. That is also the shape of a cold Cloud Run instance
before its first BigQuery call, and `ApplicationContextTest` pins it.

`pom.xml` pins Mockito and Byte Buddy above the Spring Boot 3.3.5 defaults so the suite runs
on JDK 25. Runtime targets stay on Java 21, matching Docker and CI.

---

## Deployment

Pushes to `main` run the test suite, build a multi-stage image, deploy to Cloud Run, and poll
`/health` on the new revision before the job is allowed to pass. Pull requests run tests only.

Secrets come from Secret Manager and must exist before the first deploy:

```bash
printf 'value' | gcloud secrets create API_KEY --data-file=- --project=portfolio-api-505006
printf '{"continuum":"<continuum-home token>"}' \
  | gcloud secrets create MONOLITH_CLIENT_KEYS --data-file=- --project=portfolio-api-505006
printf '<webhook url>' | gcloud secrets create DISCORD_WEBHOOK_URL --data-file=- --project=portfolio-api-505006
```

All three are wired in via `--set-secrets`. Give continuum-home the token you put under
`"continuum"` (as `MONOLITH_API_KEY` in its Vercel env), not `API_KEY` — and onboard the next app
by adding another key to the map:

```bash
gcloud secrets versions access latest --secret=MONOLITH_CLIENT_KEYS --project=portfolio-api-505006 \
  | jq '. + {"budget-cli":"<new token>"}' \
  | gcloud secrets versions add MONOLITH_CLIENT_KEYS --data-file=- --project=portfolio-api-505006
# then add {"name":"budget-cli","readScope":"budget-cli"} to clients.json
```

Note that `ApiKeyAuthFilter` treats every key identically **for ingest** — same authority on
`/postback`. The **read** path is where they differ: each client's `readScope` in
[`clients.json`](src/main/resources/clients.json) confines `/audit/logs` to one app, so a leaked
or misused client key can't read another app's history. A per-client key still buys attribution
and independent revocation (drop it from `MONOLITH_CLIENT_KEYS`, add a new version).
