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

echo "==> Project:  ${PROJECT}"
echo "==> Location: ${LOCATION}"
echo "==> Dataset:  ${DATASET}"
echo

run_step() {
  local description="$1"
  shift

  printf '  %-42s ' "${description}"

  local output
  if output=$("$@" 2>&1); then
    echo "done"
  elif grep -qi "already exists" <<<"${output}"; then
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

echo "==> Dataset"
run_step "${DATASET}" \
  bq mk --project_id="${PROJECT}" --dataset --location="${LOCATION}" "${PROJECT}:${DATASET}"
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
  ANY_VALUE(display_name IGNORE NULLS) AS display_name,
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

cat <<EOF
==> Done. Verify with:

  bq ls --project_id=${PROJECT} ${DATASET}
  bq show --project_id=${PROJECT} ${DATASET}.audit_events
  bq show --project_id=${PROJECT} ${DATASET}.identity_links
  bq query --project_id=${PROJECT} --use_legacy_sql=false 'SELECT * FROM \`${PROJECT}.${DATASET}.identities\` LIMIT 10'

Until a second sourceApp starts posting events with metadata.email, identity_links will
have exactly one row per Continuum user and identities.app_count will always read 1 —
that's expected, not a sign of a broken pipeline.
EOF
