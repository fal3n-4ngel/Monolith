# 🏛️ Monolith Data Warehouse & API Integration Guide

**Monolith** is the central data warehouse, telemetry ingest engine, and analytics audit hub for personal applications (such as **Continuum Home**, **Monolith Dashboard**, and future integrated services).

It runs on **Java 21 / Spring Boot 3** deployed to GCP Cloud Run (`https://api.adithyakrishnan.com`), backed by **Google BigQuery** (`portfolio-api-505006`), and posts real-time event heartbeats to **Discord**.

---

## 1. 🗄️ Data Storage Architecture (Google BigQuery)

Monolith stores application history as immutable event streams in BigQuery.

### Dataset Structure
- **`events` Dataset**: Per-app, per-domain tables storing domain lifecycle events (`EXPENSE_CREATED`, `WATCHLIST_ADDED`, etc.).
- **`audit` Dataset**: System-level telemetry (`audit_events`) and identity mapping tables (`identity_links`, `identities`).

### Table Naming Convention
Tables follow `{app}_{domain}` naming:
- `continuum_home_expenses`
- `continuum_home_watchlist`
- `continuum_home_investments`
- `continuum_home_subscriptions`

### Universal Domain Event Schema
All domain tables share a standardized 10-column schema partitioned by `occurred_at` (90-day retention) and clustered by `(local_user_id, event_type)` for zero-cost, high-speed filtering:

| Column Name | Type | Description |
| :--- | :--- | :--- |
| `event_id` | `STRING` | Unique event UUID (used as BigQuery `insertId` for exact deduplication). |
| `source_app` | `STRING` | Identifier of the originating app (e.g. `continuum-home`). |
| `local_user_id` | `STRING` | Acting user ID as known to the source app. |
| `event_type` | `STRING` | Allowlisted event type (e.g. `EXPENSE_CREATED`). |
| `action` | `STRING` | Event action (`CREATE`, `UPDATE`, `DELETE`). |
| `entity_id` | `STRING` | Source app's record ID (e.g. `exp_88192`). |
| `item_count` | `INT64` | Rows affected (greater than 1 for batch operations like CSV import). |
| `occurred_at` | `TIMESTAMP` | Event timestamp when the state change occurred. |
| `received_at` | `TIMESTAMP` | Timestamp when Monolith API ingested the event. |
| `payload` | `JSON` | Native JSON column containing sanitized domain-specific metadata. |

---

## 2. 🔍 Querying User Details & Activity

Because all domain tables share a uniform column structure, fetching all historical data and details for a specific user across all apps is simple and efficient.

### Query 1: Fetch All User History Across All Integrated Apps
Query the unified `events.all_events` cross-domain view to retrieve a complete timeline of user actions:

```sql
SELECT 
  domain,
  event_type,
  action,
  entity_id,
  item_count,
  occurred_at,
  payload
FROM `portfolio-api-505006.events.all_events`
WHERE local_user_id = 'YOUR_USER_ID'
ORDER BY occurred_at DESC;
```

### Query 2: Fetch User Activity Joined with User Email
Query the `events.user_activity` view to resolve user actions across apps by verified email address:

```sql
SELECT 
  email,
  domain,
  source_app,
  event_type,
  action,
  occurred_at,
  payload
FROM `portfolio-api-505006.events.user_activity`
WHERE email = 'user@example.com'
ORDER BY occurred_at DESC;
```

### Query 3: Fetch Domain-Specific Aggregates (e.g., Total Expenses Created)
```sql
SELECT 
  local_user_id,
  COUNT(1) AS total_events,
  SUM(item_count) AS total_items
FROM `portfolio-api-505006.events.continuum_home_expenses`
WHERE event_type = 'EXPENSE_CREATED'
  AND occurred_at >= TIMESTAMP_SUB(CURRENT_TIMESTAMP(), INTERVAL 30 DAY)
GROUP BY local_user_id;
```

---

## 3. 🔌 Integrating New Applications with Monolith

Applications emit events **server-to-server** after a write or state change succeeds in their backend database. 

### Endpoint
- **URL**: `POST https://api.adithyakrishnan.com/api/v1/events/postback`
- **Headers**:
  - `Content-Type: application/json`
  - `Authorization: Bearer <YOUR_MONOLITH_API_KEY>`

### Request Body Format (JSON)
```json
{
  "sourceApp": "your-new-app",
  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventType": "YOUR_EVENT_TYPE",
  "userId": "user_uid_123",
  "entityId": "item_99182",
  "itemCount": 1,
  "timestamp": 1724584284000,
  "payload": {
    "environment": "production",
    "category": "Software",
    "amount": 29.99
  }
}
```

### Client Integration Best Practices
1. **Fire-and-Forget (Next.js `after()` or Background Task):** Never block user UI responses waiting for telemetry ingestion. Use Next.js `after()` or background task queues.
2. **Batching:** Send `itemCount: 50` for bulk operations (e.g. CSV import) rather than emitting 50 separate HTTP requests.
3. **Payload Bounds:** Keep payloads under 16 KB. Do not include sensitive PII or free-text personal notes (amounts, categories, and IDs only).

---

## 4. 🎫 Requesting Integration for New Apps & Event Types

Because Monolith resolves destination tables server-side to maintain data integrity, **adding a new app or event type requires a minor backend routing addition in `monolith-api`** (`DomainEventType` enum & BigQuery DDL execution).

### How to Request Integration for a New App:

1. **Submit a Ticket:** Open an integration ticket on the official GitHub Project Board:
   👉 **[Monolith GitHub Project Board 4](https://github.com/users/fal3n-4ngel/projects/4)**

2. **Include Ticket Details:**
   - **Source App Name:** (e.g. `my-new-app`)
   - **Event Types:** (e.g. `PROJECT_CREATED`, `TASK_COMPLETED`)
   - **Target Domain:** (e.g. `tasks`, `projects`)

3. **Backend Onboarding:** Upon ticket approval, the server-side `DomainEventType` enum and BigQuery table `my_new_app_tasks` will be provisioned automatically, enabling instant postback ingestion.
