# Monolith Events API

Central domain-event ingest for [Continuum Home](https://continuum-home.vercel.app) and other
personal applications. Java 21 / Spring Boot 3, backed by BigQuery, deployed to Cloud Run behind
`api.adithyakrishnan.com`.

The service does one job: accept application-history events from a trusted server backend, route
each to its per-app, per-domain BigQuery table, and post a visible confirmation to Discord.

---

## Endpoints

| Method | Path | Auth | Notes |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/events/postback` | **bearer** | Domain-event ingest. Rate limited per IP. Returns `202 Accepted`. |
| `GET`  | `/health`, `/` | none | Liveness. Touches no backend. |
| `GET`  | `/swagger-ui.html` | none | Interactive API docs. |

`/events/*` is retained as an alias for the versioned path.

---

## Developer Documentation & Model Context Protocol (MCP) Server

- **Documentation Website:** [https://monolith.adithyakrishnan.com](https://monolith.adithyakrishnan.com) (Single-page developer portal rendered in warm parchment cream theme).
- **Model Context Protocol (MCP) Server:** `https://monolith.adithyakrishnan.com/api/mcp` (Exposes `query_user_activity`, `query_domain_events`, `get_bigquery_schema`, and `get_system_health` tools for AI agents).

### Ingest

```bash
curl -X POST https://api.adithyakrishnan.com/api/v1/events/postback \
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
| `API_KEY` | — | Static bearer key. **Required** (server rejects all authenticated requests without one). |
| `CONTINUUM_API_KEY` | — | The key Continuum presents. Also a second accepted key, so `API_KEY` can rotate without downtime. |
| `DISCORD_WEBHOOK_URL` | — | Where the "event received" ping goes. Notifications are skipped (not sent unauthenticated) if unset. |
| `GCP_PROJECT_ID` | `portfolio-api-505006` | BigQuery project. |
| `AUDIT_RATE_LIMIT_PER_MINUTE` | `120` | Per-IP ingest budget, per instance. `0` disables. |
| `AUDIT_BIGQUERY_ENABLED` | `true` | Escape hatch to disable BigQuery writes without a redeploy. |
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
   `CONTINUUM_API_KEY` — but `PostbackRateLimitFilter` still caps per-IP throughput as a backstop
   against a leaked key or a runaway caller.
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
```

Repeat for `CONTINUUM_API_KEY` and `DISCORD_WEBHOOK_URL`. All three are wired into the service
via `--set-secrets`. Give Continuum the **`CONTINUUM_API_KEY`** value (as `MONOLITH_API_KEY` in
its environment), not `API_KEY` — so the credential sitting in Vercel can be rotated
independently:

```bash
gcloud secrets versions access latest --secret=CONTINUUM_API_KEY --project=portfolio-api-505006
```

Note that `ApiKeyAuthFilter` currently treats both keys identically — same authority on every
authenticated endpoint. Separate keys buy independent rotation and attribution, **not** privilege
separation.
