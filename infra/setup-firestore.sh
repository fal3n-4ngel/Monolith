#!/usr/bin/env bash
#
# One-time Firestore setup for the audit_logs collection group.
#
# Uses gcloud rather than the Firebase CLI on purpose:
#   - no extra tooling to install, and
#   - `firebase deploy --only firestore` would also push firestore.rules, which are
#     deny-all. That is correct for this project but would lock out any client SDK
#     using the same GCP project, so it should be a deliberate separate decision.
#
# Safe to re-run: every step is additive, and existing indexes are reported and skipped.
#
# Usage:  ./infra/setup-firestore.sh [PROJECT_ID]

set -uo pipefail

PROJECT="${1:-portfolio-api-505006}"
COLLECTION="audit_logs"

echo "==> Project:    ${PROJECT}"
echo "==> Collection: ${COLLECTION}"
echo

# ---------------------------------------------------------------------------
# 1. Composite indexes backing GET /api/v1/audit/logs
#
# Every query orders by timestamp DESC and optionally filters on sourceApp,
# eventType, and severity. Firestore needs one composite index per filter
# combination that is actually used; a missing one surfaces as FAILED_PRECONDITION
# and the endpoint returns an empty list.
#
# These seven cover every combination the controller can produce. If you never
# query by some of them, delete the unused indexes — each one costs storage and
# adds write amplification on every insert.
# ---------------------------------------------------------------------------

create_index() {
  local description="$1"
  shift

  printf '  %-42s ' "${description}"

  local output
  if output=$(gcloud firestore indexes composite create \
      --project="${PROJECT}" \
      --collection-group="${COLLECTION}" \
      --query-scope=collection \
      --async \
      "$@" 2>&1); then
    echo "created"
  elif grep -qi "already exists" <<<"${output}"; then
    echo "exists"
  else
    echo "FAILED"
    sed 's/^/      /' <<<"${output}"
    return 1
  fi
}

TS="--field-config=field-path=timestamp,order=descending"

echo "==> Creating composite indexes (builds run asynchronously)"
create_index "sourceApp + timestamp" \
  --field-config=field-path=sourceApp,order=ascending "${TS}"
create_index "eventType + timestamp" \
  --field-config=field-path=eventType,order=ascending "${TS}"
create_index "severity + timestamp" \
  --field-config=field-path=severity,order=ascending "${TS}"
create_index "isUnauthorized + timestamp" \
  --field-config=field-path=isUnauthorized,order=ascending "${TS}"
create_index "sourceApp + eventType + timestamp" \
  --field-config=field-path=sourceApp,order=ascending \
  --field-config=field-path=eventType,order=ascending "${TS}"
create_index "sourceApp + severity + timestamp" \
  --field-config=field-path=sourceApp,order=ascending \
  --field-config=field-path=severity,order=ascending "${TS}"
create_index "sourceApp + eventType + severity + timestamp" \
  --field-config=field-path=sourceApp,order=ascending \
  --field-config=field-path=eventType,order=ascending \
  --field-config=field-path=severity,order=ascending "${TS}"
echo

# ---------------------------------------------------------------------------
# 2. Single-field index exemptions
#
# Firestore auto-indexes every scalar in a document, including inside the
# caller-supplied maps. None of these fields is ever queried, so each index entry
# is pure cost: billed as storage forever, and paid again in write latency on
# every insert.
# ---------------------------------------------------------------------------

echo "==> Disabling indexes on never-queried free-form fields"
for field in metadata context observed; do
  printf '  %-42s ' "${field}"
  if gcloud firestore indexes fields update "${field}" \
      --project="${PROJECT}" \
      --collection-group="${COLLECTION}" \
      --disable-indexes \
      --async >/dev/null 2>&1; then
    echo "exempted"
  else
    echo "FAILED (see: gcloud firestore indexes fields list --collection-group=${COLLECTION})"
  fi
done
echo

# ---------------------------------------------------------------------------
# 3. TTL policy
#
# Every document carries an expiresAt stamped from AUDIT_RETENTION (default 90d).
# Without this policy that field is inert and the collection grows without bound.
# ---------------------------------------------------------------------------

echo "==> Enabling TTL on expiresAt"
printf '  %-42s ' "expiresAt"
if gcloud firestore fields ttls update expiresAt \
    --project="${PROJECT}" \
    --collection-group="${COLLECTION}" \
    --enable-ttl \
    --async >/dev/null 2>&1; then
  echo "enabled"
else
  echo "FAILED or already enabled"
fi
echo

# ---------------------------------------------------------------------------

cat <<EOF
==> Done. Index builds are asynchronous; verify with:

  gcloud firestore indexes composite list --project=${PROJECT}
  gcloud firestore indexes fields list --collection-group=${COLLECTION} --project=${PROJECT}
  gcloud firestore fields ttls list --collection-group=${COLLECTION} --project=${PROJECT}

Note: the exemptions above apply to the named field. Whether they cascade to map
subfields (metadata.amount, context.environment, ...) should be confirmed in the
Firestore console under Indexes > Single field > Exemptions, which offers an explicit
"apply to all descendants" option. That cascade is where most of the saving is.
EOF
