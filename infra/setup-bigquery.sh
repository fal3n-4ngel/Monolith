#!/usr/bin/env bash
#
# One-time BigQuery setup for the audit_events / identity_links pipeline.
#
# BigQuery is additive to Firestore, not a replacement: Firestore stays the short-TTL
# operational store behind GET /api/v1/audit/logs, and this dataset is the unbounded-
# retention analytics sink plus the cross-app identity-linking tables. See
# BigQueryAuditWriter for the write path and README.md's "BigQuery setup" section for
# how the pieces fit together.
#
# Safe to re-run: every step is additive, and "already exists" is treated as success.
#
# Usage:  ./infra/setup-bigquery.sh [PROJECT_ID] [LOCATION]

set -uo pipefail

PROJECT="${1:-portfolio-api-505006}"
LOCATION="${2:-US}"
DATASET="audit"
EVENTS_DATASET="events"

# Domain tables are created per (app, domain). Add an app here and re-run to onboard it;
# the event->table routing itself lives in DomainEventType, which is the allowlist.
APPS=("continuum_home")
DOMAINS=("expenses" "watchlist" "investments" "subscriptions")

echo "==> Project:   ${PROJECT}"
echo "==> Location:  ${LOCATION}"
echo "==> Datasets:  ${DATASET} (audit), ${EVENTS_DATASET} (domain events)"
echo

run_step() {
  local description="$1"
  shift

  printf '  %-42s ' "${description}"

  local output
  if output=$("$@" 2>&1); then
    echo "done"
  # bq hard-wraps its error text, so "already\n  exists" is a real shape here —
  # collapse whitespace before matching or a re-run reports a false failure.
  elif tr -s '[:space:]' ' ' <<<"${output}" | grep -qi "already exists"; then
    echo "exists"
  else
    echo "FAILED"
    sed 's/^/      /' <<<"${output}"
    return 1
  fi
}

# ---------------------------------------------------------------------------
# 1. Dataset
# ---------------------------------------------------------------------------

echo "==> Datasets"
run_step "${DATASET}" \
  bq mk --project_id="${PROJECT}" --dataset --location="${LOCATION}" "${PROJECT}:${DATASET}"
run_step "${EVENTS_DATASET}" \
  bq mk --project_id="${PROJECT}" --dataset --location="${LOCATION}" "${PROJECT}:${EVENTS_DATASET}"
echo

# ---------------------------------------------------------------------------
# 2. audit_events — append-only fact table
#
# Partitioned by event_timestamp and clustered by source_app/event_type: the same
# cost discipline as the Firestore composite indexes in setup-firestore.sh, applied
# to BigQuery's own billing model (partitions pruned per query, not scanned whole).
#
# context/metadata are native JSON columns rather than fixed columns: their shape
# varies per source app and event type, exactly like they do in Firestore.
# ---------------------------------------------------------------------------

EVENTS_SCHEMA="event_id:STRING,log_id:STRING,source_app:STRING,event_type:STRING,\
severity:STRING,user_id:STRING,event_timestamp:TIMESTAMP,received_at:TIMESTAMP,\
is_unauthorized:BOOLEAN,resolved_origin:STRING,origin_source:STRING,\
observed_origin:STRING,observed_referer:STRING,observed_user_agent:STRING,\
observed_client_ip:STRING,context:JSON,metadata:JSON"

echo "==> Tables"
run_step "audit_events" \
  bq mk --project_id="${PROJECT}" --table \
    --time_partitioning_field=event_timestamp --time_partitioning_type=DAY \
    --clustering_fields=source_app,event_type \
    "${PROJECT}:${DATASET}.audit_events" "${EVENTS_SCHEMA}"

# ---------------------------------------------------------------------------
# 3. identity_links — upserted, one row per (source_app, local_user_id) ever seen
#    with a verified email. BigQuery doesn't enforce the uniqueness this implies —
#    that's guaranteed by BigQueryAuditWriter's MERGE, not a table constraint.
# ---------------------------------------------------------------------------

IDENTITY_SCHEMA="source_app:STRING,local_user_id:STRING,email:STRING,\
display_name:STRING,first_seen:TIMESTAMP,last_seen:TIMESTAMP"

run_step "identity_links" \
  bq mk --project_id="${PROJECT}" --table \
    "${PROJECT}:${DATASET}.identity_links" "${IDENTITY_SCHEMA}"
echo

# ---------------------------------------------------------------------------
# 4. identities view — the actual cross-app "fact linking": every app-local account
#    that shares a verified email is the same person. No separate global-user-id
#    table exists or is needed; email already is the stable cross-app key.
# ---------------------------------------------------------------------------

IDENTITIES_SQL="SELECT
  email,
  -- MAX rather than ANY_VALUE: BigQuery rejects IGNORE NULLS on ANY_VALUE, and MAX
  -- skips NULLs natively, so a user seen once without a name still resolves to one.
  MAX(display_name) AS display_name,
  ARRAY_AGG(STRUCT(source_app, local_user_id, first_seen, last_seen) ORDER BY last_seen DESC) AS linked_accounts,
  COUNT(DISTINCT source_app) AS app_count,
  MIN(first_seen) AS first_seen,
  MAX(last_seen) AS last_seen
FROM \`${PROJECT}.${DATASET}.identity_links\`
GROUP BY email"

echo "==> View"
run_step "identities" \
  bq mk --project_id="${PROJECT}" --use_legacy_sql=false \
    --view="${IDENTITIES_SQL}" "${PROJECT}:${DATASET}.identities"
echo

# ---------------------------------------------------------------------------
# 5. Domain event tables — one per (app, domain), all sharing one column set.
#
# The shared columns are the point. source_app + local_user_id appear in every
# domain table, so joining any of them to identity_links is always the same shape:
# adding a fifth domain, or a second app, never invents a new join pattern.
# ---------------------------------------------------------------------------

DOMAIN_SCHEMA="event_id:STRING,source_app:STRING,local_user_id:STRING,event_type:STRING,\
action:STRING,entity_id:STRING,item_count:INT64,occurred_at:TIMESTAMP,\
received_at:TIMESTAMP,payload:JSON"

echo "==> Domain event tables"
for app in "${APPS[@]}"; do
  for domain in "${DOMAINS[@]}"; do
    run_step "${app}_${domain}" \
      bq mk --project_id="${PROJECT}" --table \
        --time_partitioning_field=occurred_at --time_partitioning_type=DAY \
        --clustering_fields=local_user_id,event_type \
        "${PROJECT}:${EVENTS_DATASET}.${app}_${domain}" "${DOMAIN_SCHEMA}"
  done
done
echo

# ---------------------------------------------------------------------------
# 6. user_activity view — every domain event from every app in one stream, resolved
#    to a person via the shared (source_app, local_user_id) key.
#
# This is the payoff of the uniform column set: onboarding a new domain or app means
# adding one more UNION ALL branch here, not designing a new join.
# ---------------------------------------------------------------------------

ACTIVITY_BRANCHES=""
for app in "${APPS[@]}"; do
  for domain in "${DOMAINS[@]}"; do
    [ -n "${ACTIVITY_BRANCHES}" ] && ACTIVITY_BRANCHES="${ACTIVITY_BRANCHES}
  UNION ALL"
    ACTIVITY_BRANCHES="${ACTIVITY_BRANCHES}
  SELECT '${domain}' AS domain, * FROM \`${PROJECT}.${EVENTS_DATASET}.${app}_${domain}\`"
  done
done

ACTIVITY_SQL="WITH all_events AS (${ACTIVITY_BRANCHES}
)
SELECT
  i.email,
  e.domain,
  e.source_app,
  e.event_type,
  e.action,
  e.entity_id,
  e.item_count,
  e.occurred_at,
  e.payload
FROM all_events e
LEFT JOIN \`${PROJECT}.${DATASET}.identity_links\` i
  ON i.source_app = e.source_app AND i.local_user_id = e.local_user_id"

echo "==> Cross-app activity view"
run_step "user_activity" \
  bq mk --project_id="${PROJECT}" --use_legacy_sql=false \
    --view="${ACTIVITY_SQL}" "${PROJECT}:${EVENTS_DATASET}.user_activity"
echo

cat <<EOF
==> Done. Verify with:

  bq ls --project_id=${PROJECT} ${DATASET}
  bq ls --project_id=${PROJECT} ${EVENTS_DATASET}
  bq show --project_id=${PROJECT} ${DATASET}.audit_events
  bq show --project_id=${PROJECT} ${EVENTS_DATASET}.continuum_home_expenses
  bq query --project_id=${PROJECT} --use_legacy_sql=false 'SELECT * FROM \`${PROJECT}.${DATASET}.identities\` LIMIT 10'
  bq query --project_id=${PROJECT} --use_legacy_sql=false 'SELECT * FROM \`${PROJECT}.${EVENTS_DATASET}.user_activity\` ORDER BY occurred_at DESC LIMIT 10'

Until a second sourceApp starts posting events with metadata.email, identity_links will
have exactly one row per Continuum user and identities.app_count will always read 1 —
that's expected, not a sign of a broken pipeline.

To onboard a new app: add its normalized name to APPS above, re-run this script, and add
its event types to DomainEventType. To add a domain: add it to DOMAINS and to that enum.
EOF
