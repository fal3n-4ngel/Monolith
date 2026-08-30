#!/usr/bin/env bash
#
# One-time BigQuery setup for the domain-event ingest pipeline.
#
# Safe to re-run: every step is additive, and "already exists" is treated as success.
#
# Usage:  ./infra/setup-bigquery.sh [PROJECT_ID] [LOCATION]

set -uo pipefail

PROJECT="${1:-portfolio-api-505006}"
LOCATION="${2:-US}"
DATASET="events"

# Domain tables are created per (app, domain). Add an app here and re-run to onboard it;
# the event->table routing itself lives in DomainEventType, which is the allowlist.
APPS=("continuum_home")
DOMAINS=("expenses" "watchlist" "investments" "subscriptions" "account")

# Apps whose domains differ from the standard set, as "table:domain".
EXTRA_TABLES=("monolith_dashboard_usage:usage")

TABLES=()
for app in "${APPS[@]}"; do
  for domain in "${DOMAINS[@]}"; do
    TABLES+=("${app}_${domain}:${domain}")
  done
done
TABLES+=("${EXTRA_TABLES[@]}")

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

echo "==> Dataset"
run_step "${DATASET}" \
  bq mk --project_id="${PROJECT}" --dataset --location="${LOCATION}" "${PROJECT}:${DATASET}"
echo

# ---------------------------------------------------------------------------
# 2. Domain event tables — one per (app, domain), all sharing one column set.
#
# The shared columns are the point. source_app + local_user_id appear in every
# domain table, so a future cross-app join (once a second app posts events) is
# always the same shape: adding a fifth domain, or a second app, never invents
# a new one. Partitioned by occurred_at and clustered by local_user_id/event_type
# for the same reason the old Firestore setup indexed by those — cheap filtering.
#
# payload is a native JSON column: its shape varies per domain and event type,
# so it stays free-form rather than forcing a fixed schema across all of them.
# ---------------------------------------------------------------------------

DOMAIN_SCHEMA="event_id:STRING,source_app:STRING,local_user_id:STRING,event_type:STRING,\
action:STRING,entity_id:STRING,item_count:INT64,occurred_at:TIMESTAMP,\
received_at:TIMESTAMP,payload:JSON"

echo "==> Domain event tables"
for entry in "${TABLES[@]}"; do
  table="${entry%%:*}"
  run_step "${table}" \
    bq mk --project_id="${PROJECT}" --table \
      --time_partitioning_field=occurred_at --time_partitioning_type=DAY \
      --clustering_fields=local_user_id,event_type \
      "${PROJECT}:${DATASET}.${table}" "${DOMAIN_SCHEMA}"
done
echo

# ---------------------------------------------------------------------------
# 3. all_events view — every domain event from every app in one queryable stream.
#
# CREATE OR REPLACE, not `bq mk --view`: the latter no-ops once the view exists,
# so a new table added above would never make it into the union.
# ---------------------------------------------------------------------------

ACTIVITY_SQL=""
for entry in "${TABLES[@]}"; do
  table="${entry%%:*}"
  domain="${entry##*:}"
  [ -n "${ACTIVITY_SQL}" ] && ACTIVITY_SQL="${ACTIVITY_SQL}
  UNION ALL"
  ACTIVITY_SQL="${ACTIVITY_SQL}
  SELECT '${domain}' AS domain, * FROM \`${PROJECT}.${DATASET}.${table}\`"
done

echo "==> Cross-domain activity view"
run_step "all_events" \
  bq query --project_id="${PROJECT}" --use_legacy_sql=false --nouse_legacy_sql \
    "CREATE OR REPLACE VIEW \`${PROJECT}.${DATASET}.all_events\` AS ${ACTIVITY_SQL}"
echo

cat <<EOF
==> Done. Verify with:

  bq ls --project_id=${PROJECT} ${DATASET}
  bq show --project_id=${PROJECT} ${DATASET}.continuum_home_expenses
  bq query --project_id=${PROJECT} --use_legacy_sql=false 'SELECT * FROM \`${PROJECT}.${DATASET}.all_events\` ORDER BY occurred_at DESC LIMIT 10'

To onboard a new app: add its normalized name to APPS above (or, for a non-standard domain
set, an entry to EXTRA_TABLES), re-run this script, and add its event types to DomainEventType.
To add a domain: add it to DOMAINS and to that enum.
EOF
