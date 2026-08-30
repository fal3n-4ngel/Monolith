# 🚀 Monolith Application Integration Guide

This guide provides step-by-step technical instructions for connecting a new application to Monolith's server-to-server telemetry ingestion engine and BigQuery analytical data warehouse.

---

## 📋 Integration Process Overview

```
[ Step 1: GitHub Ticket ] ──► [ Step 2: Monolith Provisioning ] ──► [ Step 3: Dispatch Helper ]
 Submit app name & events       Register SourceApp & Domain       Implement non-blocking POST
```

---

## Step 1: Submit Integration Ticket (GitHub Board #4)
To maintain fail-closed schema security, Monolith validates every incoming `sourceApp` and `eventType` against server enums (`SourceApp.java` and `DomainEventType.java`).

1. Open an integration ticket on **[GitHub Project Board #4](https://github.com/users/fal3n-4ngel/projects/4)**.
2. Provide your integration parameters:
   - **`sourceApp`**: Unique application identifier (e.g., `"my-task-app"`).
   - **`Domain`**: Target domain group (e.g., `"tasks"`, `"finance"`, `"media"`).
   - **`DomainEventTypes`**: List of event names to allowlist (e.g., `"TASK_CREATED"`, `"TASK_COMPLETED"`).

---

## Step 2: Monolith Allowlist & Table Provisioning
Upon ticket approval, the Monolith maintainer provisions:
1. **`SourceApp.java`**: Adds enum constant `MY_TASK_APP("my-task-app")`.
2. **`DomainEventType.java`**: Registers event enums (e.g., `TASK_COMPLETED("tasks", Action.CREATE)`).
3. **BigQuery Destination Table**: Provisions `portfolio-api-505006.events.my_task_app_tasks` with:
   - **Partitioning:** `DAY on occurred_at (Permanent / Infinite Retention - No Expiration)`
   - **Clustering:** `(local_user_id, event_type)`

---

## Step 3: Implement Postback Dispatcher in Your App

### 📡 Ingestion Endpoint
- **URL:** `POST https://monolith-postbacks.adithyakrishnan.com/api/v1/events/postback`
- **Header:** `Authorization: Bearer <MONOLITH_API_KEY>` or `Authorization: Bearer <GOOGLE_ID_TOKEN>`
- **Content-Type:** `application/json`

### 📦 Standard JSON Postback Payload
```json
{
  "sourceApp": "my-task-app",
  "eventId": "a7b8c9d0-1234-5678-90ab-cdef12345678",
  "eventType": "TASK_COMPLETED",
  "userId": "usr_99182",
  "entityId": "task_4412",
  "itemCount": 1,
  "timestamp": 1787726400000,
  "payload": {
    "userEmail": "user@example.com",
    "title": "Complete Monolith Integration",
    "environment": "production"
  }
}
```

---

## 💻 Code Reference Implementation

### TypeScript / Next.js
```typescript
import { after } from "next/server";

export function recordDomainEvent(event: {
  eventType: string;
  userId: string;
  userEmail: string;
  entityId?: string;
  payload?: Record<string, any>;
}) {
  after(async () => {
    await fetch("https://monolith-postbacks.adithyakrishnan.com/api/v1/events/postback", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${process.env.MONOLITH_API_KEY}`,
      },
      body: JSON.stringify({
        sourceApp: "my-task-app",
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

### Python
```python
import requests
import uuid
import time
import os

def send_domain_event(event_type: str, user_id: str, user_email: str, payload: dict):
    url = "https://monolith-postbacks.adithyakrishnan.com/api/v1/events/postback"
    headers = {
        "Authorization": f"Bearer {os.environ['MONOLITH_API_KEY']}",
        "Content-Type": "application/json"
    }
    body = {
        "sourceApp": "my-python-app",
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "userId": user_id,
        "timestamp": int(time.time() * 1000),
        "payload": {
            **payload,
            "userEmail": user_email,
            "environment": "production"
        }
    }
    requests.post(url, json=body, timeout=3.0)
```

---

## Step 4: Read Your Events Back

### 🔎 Query Endpoint
- **URL:** `GET https://monolith-postbacks.adithyakrishnan.com/api/v1/audit/logs`
- **Header:** `Authorization: Bearer <MONOLITH_API_KEY>` — the same key you post with.

```bash
curl -s -G https://monolith-postbacks.adithyakrishnan.com/api/v1/audit/logs \
  -H "Authorization: Bearer $MONOLITH_API_KEY" \
  --data-urlencode 'userId=usr_99182' \
  --data-urlencode 'eventType=TASK_COMPLETED' \
  --data-urlencode 'limit=100'
```

```json
{ "scope": "my-task-app", "count": 1,
  "results": [
    { "domain": "tasks", "eventId": "a7b8c9d0-…", "sourceApp": "my-task-app",
      "userId": "usr_99182", "eventType": "TASK_COMPLETED", "action": "CREATE",
      "entityId": "task_4412", "itemCount": 1,
      "occurredAt": "2026-08-30T12:00:00Z", "receivedAt": "2026-08-30T12:00:01Z",
      "payload": { "title": "Complete Monolith Integration" } } ],
  "nextBefore": "2026-08-30T12:00:00Z" }
```

| Param | Notes |
| :--- | :--- |
| `userId` | Acting user id as your app knows it. |
| `domain` / `eventType` | Checked against the same allowlists as ingest. |
| `from` / `before` | `occurred_at` bounds (ISO-8601 or epoch millis). No `from` &rarr; last 30 days only. |
| `limit` | Default 50, max 200. |
| `sourceApp` | **Not for app keys** — your results are fixed to your own app. Passing another app's id is a **403**. |

Paginate by passing the previous response's `nextBefore` as `before`.

---

## 🔐 Security & Fails-Closed Policy
- **Fails-Closed Ingestion:** Unregistered `sourceApp` or `eventType` values return HTTP **400 Bad Request** (`"unknown_source_app"` / `"unknown_event_type"`).
- **Authentication:** Unauthenticated requests return HTTP **401 Unauthorized**.
- **Non-Blocking Delivery:** Postbacks return HTTP **202 Accepted** immediately; BigQuery writes and Discord alerts run on async background threads.
- **Read Isolation:** `GET /api/v1/audit/logs` fixes the `source_app` filter to the calling key's app. Cross-app reads require a dedicated cross-app credential; an app key asking for another app gets HTTP **403 Forbidden**. Reads are rate limited per key (30/min default), independently of ingest.
