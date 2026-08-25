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
| `POST` | `/api/v1/audit/postback` | none | Ingest. Rate limited per IP. Returns `202 Accepted`. |
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
| `CONTINUUM_API_KEY` | — | Second accepted key, for rotation without downtime. |
| `ALLOWED_EMAIL` | — | The only Google identity accepted for OAuth auth. |
| `DISCORD_WEBHOOK_URL` | — | Security alert destination. Alerts are logged only if unset. |
| `GCP_PROJECT_ID` | `portfolio-api-505006` | Firestore project. |
| `AUDIT_AUTHORIZED_ORIGINS` | Continuum + localhost | Comma-separated allow-list. |
| `AUDIT_KNOWN_SOURCE_APPS` | `continuum-home` | Apps whose identity is worth defending. |
| `AUDIT_RETENTION` | `90d` | Drives the `expiresAt` field consumed by the Firestore TTL policy. |
| `AUDIT_RATE_LIMIT_PER_MINUTE` | `120` | Per-IP ingest budget, per instance. `0` disables. |
| `AUDIT_ALERT_COOLDOWN` | `15m` | Duplicate-alert suppression window. |
| `AUDIT_HASH_CLIENT_IP` | `false` | Store a SHA-256 pseudonym instead of the raw IP. |

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
