# 🚀 Monolith Application Integration Guide

How to connect a new application to Monolith's server-to-server event ingest and its BigQuery
warehouse. Onboarding is **one file change and a merge** — no Java edits, no manual BigQuery
setup, no new Secret Manager entry.

---

## Integration at a glance

```
[ 1. Ticket ]  ──►  [ 2. One PR: a block in apps.json ]  ──►  [ 3. Merge ]  ──►  [ 4. Wire up the postback ]
 app id + events        adds the app to the allowlist          CI deploys;         non-blocking POST from
                        (+ optional reports.json block)        tables + view +      your backend
                                                               API key auto-
                                                               provision on boot
```

---

## Step 1 — Open an integration ticket

Open a ticket on **[GitHub Project Board 4](https://github.com/users/fal3n-4ngel/projects/4)**
using the **App Integration Request** template, stating:

- **App id** — lowercase, dashes only, e.g. `task-app`. Becomes the `sourceApp` value and the
  BigQuery table prefix (`task_app_*`).
- **Domains** — the groupings your events fall into, e.g. `tasks`, `projects`.
- **Event names** — `UPPER_SNAKE_CASE`, unique across every app, e.g. `TASK_CREATED`,
  `TASK_COMPLETED`. Names ending `_CREATED/_ADDED/_LOGGED`, `_UPDATED/_CHANGED`,
  `_DELETED/_REMOVED` get their action inferred; anything else needs `:create|update|delete`.
- **Read-back?** — whether the app should be able to `GET` its own history, and which
  `reports.json` reports it may run (if any).

---

## Step 2 — One PR: add the app to `apps.json`

Add a block to [`src/main/resources/apps.json`](src/main/resources/apps.json):

```json
{
  "id": "task-app",
  "readback": { "reports": ["audit-log"] },
  "events": {
    "tasks":    ["TASK_CREATED", "TASK_UPDATED", "TASK_COMPLETED:update", "TASK_DELETED"],
    "projects": ["PROJECT_CREATED", "PROJECT_ARCHIVED"]
  }
}
```

- Omit `readback` entirely if the app only writes.
- To pin domain-specific reports to this app, add them to
  [`reports.json`](src/main/resources/reports.json) with `"tags": ["task-app"]` in the same PR.

### What CI checks on the PR

The **Validate registries** workflow runs on any PR touching `apps.json` / `clients.json` /
`reports.json` (or the registry code) and fails on:

- malformed JSON;
- a bad app id, a duplicate or suffix-less event name, an event name already used by another app;
- a report `tag` that isn't a registered app;
- a `readback.reports` (or `clients.json` `reports`) id that doesn't exist in `reports.json`;
- an app allotted a report tagged for a *different* app (it could never run it).

The full `mvn verify` in the deploy workflow is still the merge gate; this is the fast signal.

---

## Step 3 — Merge

CI runs the suite, builds the image, and deploys to Cloud Run. On the new revision's first boot:

| What | Where | Result |
| :--- | :--- | :--- |
| **BigQuery tables** | `BigQuerySchemaProvisioner` | `task_app_tasks`, `task_app_projects` created (`CREATE TABLE IF NOT EXISTS`), partitioned by day, clustered on `(local_user_id, event_type)`. |
| **`all_events` view** | same | Rebuilt (`CREATE OR REPLACE VIEW`) to union the new tables, so read-back and reports see them immediately. |
| **API key** | `ClientKeyMap` | Derived from the app id and the `MONOLITH_KEY_SEED` secret — no Secret Manager change. |
| **Read credential** | `ClientRegistry` | If `readback` was set, a self-scoped credential named `task-app` is synthesized automatically. |

Provisioning is idempotent and fail-soft: a re-run is a no-op, and a BigQuery hiccup is logged
and retried on the next boot rather than failing the deploy.

### The app's key

The bearer key is deterministic:

```
mono_k1_<base64url( HMAC-SHA256( MONOLITH_KEY_SEED, "monolith:client-key:v1:<app-id>" ) )>
```

The maintainer computes it once and hands it over out-of-band. To pin a pre-existing key
instead, add `"<app-id>": "<token>"` to the `MONOLITH_CLIENT_KEYS` secret — an explicit entry
always wins over derivation.

> First-time setup only: the `MONOLITH_KEY_SEED` secret must exist
> (`gcloud secrets create MONOLITH_KEY_SEED`) and be listed in `deploy.yml`'s `--set-secrets`.

---

## Step 4 — Emit events from your app

Server-to-server, after the write has committed in your own database — never from a browser.

### Endpoint

- **URL:** `POST https://monolith-postbacks.adithyakrishnan.com/api/v1/events/postback`
- **Header:** `Authorization: Bearer <your key>`
- **Content-Type:** `application/json`

### Payload

```json
{
  "sourceApp": "task-app",
  "eventId": "a7b8c9d0-1234-5678-90ab-cdef12345678",
  "eventType": "TASK_COMPLETED",
  "userId": "usr_99182",
  "entityId": "task_4412",
  "itemCount": 1,
  "timestamp": 1787726400000,
  "payload": { "userEmail": "user@example.com", "environment": "production" }
}
```

### Reference dispatcher (TypeScript / Next.js)

```typescript
import { after } from "next/server";

export function recordDomainEvent(event: {
  eventType: string;
  userId: string;
  userEmail: string;
  entityId?: string;
  payload?: Record<string, unknown>;
}) {
  after(async () => {
    await fetch("https://monolith-postbacks.adithyakrishnan.com/api/v1/events/postback", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${process.env.MONOLITH_API_KEY}`,
      },
      body: JSON.stringify({
        sourceApp: "task-app",
        eventId: crypto.randomUUID(),
        eventType: event.eventType,
        userId: event.userId,
        entityId: event.entityId,
        itemCount: 1,
        timestamp: Date.now(),
        payload: {
          ...event.payload,
          userEmail: event.userEmail,
          environment: process.env.NODE_ENV || "production",
        },
      }),
    });
  });
}
```

**Practices:** fire-and-forget (never block a user response on telemetry); send `itemCount: 50`
for a bulk operation rather than 50 requests; keep payloads under 16 KB and free of PII or
free-text notes — amounts, categories, and IDs only.

---

## Step 5 — Read your events back

`GET https://monolith-postbacks.adithyakrishnan.com/api/v1/audit/logs`, same bearer key.

```bash
curl -s -G https://monolith-postbacks.adithyakrishnan.com/api/v1/audit/logs \
  -H "Authorization: Bearer $MONOLITH_API_KEY" \
  --data-urlencode 'userId=usr_99182' \
  --data-urlencode 'eventType=TASK_COMPLETED' \
  --data-urlencode 'limit=100'
```

Returns `{ scope, count, results[], nextBefore }`, newest first. Filters: `userId`,
`domain`, `eventType`, `from` / `before` (ISO-8601 or epoch millis), `limit` (default 50,
max 200). Paginate by passing the previous `nextBefore` as `before`. With no `from`, only the
last 30 days are scanned.

**Isolation.** `source_app` is fixed to your key's app; `?sourceApp=<other app>` is a `403`.
Cross-app reads need a dedicated cross-app credential.

---

## Fail-closed policy

- **Unregistered `sourceApp` or `eventType` → `400`** (`unknown_source_app` / `unknown_event_type`), nothing stored.
- **No / bad key → `401`.** Reads for another app → `403`.
- **`202 Accepted`** on ingest: validated and queued; the BigQuery write is off the request thread, not a durability guarantee.
- Per-IP and per-credential rate limits apply to ingest and to each read independently.
