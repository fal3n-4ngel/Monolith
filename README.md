# Monolith Audit & Telemetry API

Central audit-log receiver for [Continuum Home](https://continuum-home.vercel.app) and the
other personal applications. Java 21 / Spring Boot 3, backed by Firestore, deployed to
Cloud Run behind `api.adithyakrishnan.com`.

The service does one job: accept telemetry from clients that may be anywhere, decide whether
the caller is who it claims to be, and persist a bounded record cheaply.

---

## Endpoints

| Method | Path | Auth | Notes |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/v1/audit/postback` | none | Audit ingest. Rate limited per IP. Returns `202 Accepted`. |
| `POST` | `/api/v1/events/postback` | **bearer** | Domain-event ingest. Rate limited per IP. Returns `202 Accepted`. |
| `GET`  | `/api/v1/audit/logs` | bearer | Query the audit trail. |
| `GET`  | `/health`, `/` | none | Liveness. Touches no backend. |
| `GET`  | `/swagger-ui.html` | none | Interactive API docs. |

`/audit/*` and `/api/audit/*` are retained as aliases for existing clients.

### Ingest

```bash
curl -X POST https://api.adithyakrishnan.com/api/v1/audit/postback -H 'Content-Type: application/json' -d '{"sourceApp":"continuum-home","eventType":"USER_SESSION_ACTIVE","severity":"INFO","userId":"usr_1"}'
```

`202 Accepted` means validated and queued. Persistence happens off the request thread, so
**the response is not a durability guarantee**. This is deliberate: telemetry must never add
latency to, or fail, the flow it observes.

### Query

```bash
curl -H "Authorization: Bearer $API_KEY" 'https://api.adithyakrishnan.com/api/v1/audit/logs?sourceApp=continuum-home&severity=WARN&limit=50'
```

Accepts a static API key or a Google ID token whose email matches `ALLOWED_EMAIL`.
Credentials are read from the `Authorization` header only — there is no `?key=` fallback,
because that writes the secret into Cloud Run request logs and browser history.

A ready-to-run request collection for both endpoints is in [`bruno/Audit`](bruno/Audit) —
open the `bruno/` folder in [Bruno](https://www.usebruno.com) and select the `Local` or
`Production` environment.

---

## How origin verification works

The service exists partly to detect its own client being cloned and redeployed. Two rules
govern that decision:

**Server-observed headers outrank the request body.** A thief controls every byte of the JSON
payload, so `context.clientOrigin` is a hint, never evidence. Precedence is
`Origin` header → `Referer` header → body. What was actually observed is stored under
`observed`, separate from the caller's claims under `context`.

**Origins match on host, not prefix.** Both sides are reduced to a canonical
`scheme://host[:port]` before comparison, so `https://continuum-home.vercel.app.attacker.io`
does not pass as authorized and `http://localhost:30000` does not pass as `:3000`.

A postback claiming a **known** `sourceApp` from an unauthorized origin is flagged
`isUnauthorized`, persisted, and paged to Discord — at most once per origin per
`audit.alert-cooldown`. An unknown `sourceApp` from an unknown origin is simply recorded;
someone else's app posting telemetry is not brand theft.

---

## Configuration

Everything is bound through `AuditProperties` (prefix `audit.`) so cost-relevant knobs are
tunable without a code change.

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `API_KEY` | — | Static bearer key for the query endpoint. **Required.** |
| `CONTINUUM_API_KEY` | — | The key Continuum presents for domain events. Also a second accepted key for rotating `API_KEY` without downtime. |
| `ALLOWED_EMAIL` | — | The only Google identity accepted for OAuth auth. |
| `DISCORD_WEBHOOK_URL` | — | Security alert destination. Alerts are logged only if unset. |
| `GCP_PROJECT_ID` | `portfolio-api-505006` | Firestore project. |
| `AUDIT_AUTHORIZED_ORIGINS` | Continuum + localhost | Comma-separated allow-list. |
| `AUDIT_KNOWN_SOURCE_APPS` | `continuum-home` | Apps whose identity is worth defending. |
| `AUDIT_RETENTION` | `90d` | Drives the `expiresAt` field consumed by the Firestore TTL policy. |
| `AUDIT_RATE_LIMIT_PER_MINUTE` | `120` | Per-IP ingest budget, per instance. `0` disables. |
| `AUDIT_ALERT_COOLDOWN` | `15m` | Duplicate-alert suppression window. |
| `AUDIT_HASH_CLIENT_IP` | `false` | Store a SHA-256 pseudonym instead of the raw IP. |
| `AUDIT_BIGQUERY_ENABLED` | `true` | Escape hatch to disable BigQuery writes without a redeploy. |
| `AUDIT_BIGQUERY_DATASET` | `audit` | BigQuery dataset for `audit_events` / `identity_links`. |
| `AUDIT_BIGQUERY_DOMAIN_DATASET` | `events` | BigQuery dataset for per-app domain-event tables. |
| `AUDIT_BIGQUERY_LOCATION` | `US` | Dataset location. Fixed at creation time — see BigQuery setup below. |

**No secret has a default in `application.yml`.** A missing `API_KEY` makes the service reject
every authenticated request and log an error at startup — it does not fall back to open access.

---

## Firestore setup

Required once per project — the composite indexes back the query endpoint; without them
`/logs` returns an empty list and logs `FAILED_PRECONDITION`.

```bash
./infra/setup-firestore.sh portfolio-api-505006
```

The script is idempotent (safe to re-run) and does three things:

1. **Composite indexes** for every `sourceApp`/`eventType`/`severity` filter combination the
   query endpoint can produce.
2. **Index exemptions** on `metadata`, `context`, and `observed`. Firestore auto-indexes every
   scalar field in a caller-supplied map by default — left alone, a ten-key metadata map
   creates roughly twenty index entries per event, billed as storage forever and paid again in
   write latency on every insert. None of those fields are ever queried.
3. **A TTL policy** on `expiresAt`, the field every document carries stamped from
   `AUDIT_RETENTION`. Without this the collection grows without bound.

`infra/firestore.rules` denies all client-SDK access — the service reaches Firestore with
Application Default Credentials, which bypass rules, so a leaked Firebase web config can't be
used to read the audit trail. `infra/firestore.indexes.json` is the declarative source the
script applies; keep the two in sync if you add a new query filter.

---

## BigQuery setup

Firestore stays the short-retention operational store behind `/api/v1/audit/logs`. BigQuery is
additive to it, not a replacement — an unbounded-retention analytics sink, and the home of
cross-app identity linking.

```bash
./infra/setup-bigquery.sh portfolio-api-505006 US
```

Idempotent, and creates one dataset (`audit`), two tables, and one view:

1. **`audit_events`** — append-only fact table, one row per postback. Partitioned by
   `event_timestamp` and clustered by `source_app, event_type`, the same cost discipline as the
   Firestore composite indexes above. `context` and `metadata` are native `JSON` columns rather
   than fixed columns, since their shape varies per source app and event type.
2. **`identity_links`** — upserted (`MERGE`, in `BigQueryAuditWriter`), one row per
   `(source_app, local_user_id)` ever seen with a verified email. Populated opportunistically:
   whenever an incoming event's `metadata.email` is present, regardless of `eventType`.
3. **`identities`** (view) — the actual cross-app **fact linking**: groups `identity_links` by
   `email` and aggregates every `(source_app, local_user_id)` pair under it. A person's identity
   is "whatever set of app accounts share a verified email," computed at query time — there is
   no separately maintained global-user-id table, because email already is the stable one.

**Email is the only matching key. Name is stored for display only, never used to link two
accounts.** Google Sign-In gives a verified email; names collide across distinct real people and
would silently merge them. `AUDIT_KNOWN_SOURCE_APPS` already anticipates more than one
`sourceApp` — until a second app actually posts `metadata.email`, `identity_links` will have
exactly one row per Continuum user and `identities.app_count` will always read `1`. That's the
pipeline waiting for a second app, not a broken one.

`eventId` (client-generated, see `lib/audit-postback/client.ts` in Continuum) is used as the
BigQuery `insertId` on the `audit_events` insert, so a retried or `keepalive`-resent postback
lands once instead of twice.

---

## Domain events

Audit events are security facts about sessions and access. **Domain events** are application
history — an expense created, a watchlist item removed — and are a deliberately separate
pipeline, not more `eventType` values on the audit one.

Three things differ, and each is the reason for the split:

| | Audit postback | Domain event |
| :--- | :--- | :--- |
| Auth | none (a browser must be able to call it) | **bearer key required** |
| Emitted by | browser *and* server | source app's **server only** |
| Retention | 90d TTL | indefinite |

The authentication difference is the important one. A domain event is only ever emitted by a
source app's own backend after a write has already committed, so the endpoint can demand a
credential — and does. Without that, anyone who found the URL could fabricate an expense record
in the analytics store. Continuum only sends these when `MONOLITH_API_KEY` is set; unset means
the events are skipped rather than sent unauthenticated.

### Callers name an event, never a table

The destination is resolved server-side from `eventType` by `DomainEventType`, which is both
the allowlist and the routing table. `EXPENSE_CREATED` from `continuum-home` lands in
`events.continuum_home_expenses`.

Letting the client name its own table was the obvious alternative and is worse: table
identifiers can't be parameterized the way values can, so a caller-supplied destination becomes
a hand-rolled validation problem — and schema ownership drifts to whoever is calling the
endpoint. Routing here costs one enum entry per event and keeps that decision in this repo. An
`eventType` outside the allowlist returns `400 REJECTED` and stores nothing, so a client typo
surfaces immediately instead of quietly becoming a gap.

### One column set across every table

Every domain table — for every app, every domain — has the same columns: `event_id`,
`source_app`, `local_user_id`, `event_type`, `action`, `entity_id`, `item_count`, `occurred_at`,
`received_at`, `payload`.

That uniformity is what makes cross-app querying tractable. Because `source_app` +
`local_user_id` appear everywhere, joining any domain table to `identity_links` is always the
same shape, and `events.user_activity` unions all of them into one person-resolved stream:

```sql
SELECT email, domain, event_type, occurred_at
FROM `portfolio-api-505006.events.user_activity`
WHERE email = 'someone@example.com'
ORDER BY occurred_at DESC
```

Adding a domain or onboarding an app adds a `UNION ALL` branch, never a new join pattern.

### Batching

Batch operations emit **one** event carrying `itemCount`, not one per row. A 200-row CSV import
sending 200 postbacks would exhaust `AUDIT_RATE_LIMIT_PER_MINUTE` and add no information.

### Payloads keep structure, not content

Continuum sends amounts, categories, and dates — not expense titles or notes. Free-text personal
content stays in Firestore, encrypted. `PayloadSanitizer` then bounds and redacts whatever does
arrive, the same as for audit metadata.

---

## Cost model

The service sits in the Cloud Run and Firestore free tiers under normal personal use. The
things that could take it out of them, in order:

1. **Unbounded ingest.** The endpoint is public and its URL ships in a public JS bundle.
   `PostbackRateLimitFilter` caps per-IP throughput; for a hard global ceiling, put Cloud
   Armor in front of the service rather than growing this filter.
2. **Index amplification.** Addressed by the Firestore setup above.
3. **Unbounded retention.** Addressed by the TTL policy above.
4. **Payload size.** `PayloadSanitizer` caps entry count, value length, and nesting depth.
5. **Log ingestion.** gRPC and Firestore client logging is pinned to `WARN`; at `INFO` the
   Firestore client emits a channel-state line per RPC, which is billed.
6. **BigQuery streaming inserts.** `BigQueryAuditWriter` uses the plain `insertAll` streaming
   API rather than the Storage Write API: at this project's volume the difference is pennies,
   and `insertAll`'s per-row `insertId` gives exact-dedup semantics the Storage Write API's
   default stream doesn't. If ingest volume ever grows enough for this line item to matter,
   that's the thing to revisit.

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

Firestore is a `@Lazy` bean, so the application starts without GCP credentials — writes are
skipped and logged instead. That is also the shape of a cold Cloud Run instance before its
first Firestore call, and `ApplicationContextTest` pins it.

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

Repeat for `CONTINUUM_API_KEY`, `ALLOWED_EMAIL`, and `DISCORD_WEBHOOK_URL`.

`API_KEY` and `CONTINUUM_API_KEY` are both wired into the service via `--set-secrets`. Give
Continuum the **`CONTINUUM_API_KEY`** value (as `MONOLITH_API_KEY` in its environment), not
`API_KEY` — so the credential sitting in Vercel can be rotated without touching the one used to
query `/api/v1/audit/logs`:

```bash
gcloud secrets versions access latest --secret=CONTINUUM_API_KEY --project=portfolio-api-505006
```

Note that `ApiKeyAuthFilter` currently treats both keys identically — same authority on every
authenticated endpoint. Separate keys buy independent rotation and attribution, **not** privilege
separation. Scoping Continuum to events-only would need per-key authorities in that filter.
