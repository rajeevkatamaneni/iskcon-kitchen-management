#!/usr/bin/env bash
# Local development documents bucket — the GCS counterpart to docker-compose.yml.
#
# Deliberately NOT in the deploy Terraform (infra/environment): that layer's
# documents bucket is per-deployed-environment, and a dev bucket sharing its
# state would clobber it. This is a developer-machine convenience, created once,
# so E2-S5 (recipe PDFs) develops against real GCS instead of a fake. Settings
# match the Terraform documents bucket exactly.
#
# Prereqs: gcloud authed (gcloud auth login) + ADC (gcloud auth application-default login).
# Idempotent: safe to re-run.
set -euo pipefail

PROJECT="${GCP_PROJECT:-iskcon-kms-2026}"
REGION="${GCP_REGION:-asia-south1}"
BUCKET="${PROJECT}-kms-dev-docs"

if gcloud storage buckets describe "gs://${BUCKET}" --project "${PROJECT}" >/dev/null 2>&1; then
  echo "Bucket gs://${BUCKET} already exists."
else
  gcloud storage buckets create "gs://${BUCKET}" \
    --project="${PROJECT}" --location="${REGION}" \
    --uniform-bucket-level-access --public-access-prevention
fi

tmp="$(mktemp)"
cat > "${tmp}" <<'JSON'
{"rule":[{"action":{"type":"Delete"},"condition":{"age":90,"matchesPrefix":["generated/"]}}]}
JSON
gcloud storage buckets update "gs://${BUCKET}" --lifecycle-file="${tmp}"
rm -f "${tmp}"

echo "Ready: gs://${BUCKET}"
echo "Point the backend at it:  export DOCUMENTS_BUCKET=${BUCKET}"
