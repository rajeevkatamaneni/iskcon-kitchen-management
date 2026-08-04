#!/usr/bin/env bash
#
# Build both container images with Cloud Build, push to Artifact Registry, and
# roll them out to Cloud Run.
#
# Usage:
#   ./deploy.sh <project-id> [environment]
#
# Assumes infra/bootstrap and infra/environment have both been applied.

set -euo pipefail

PROJECT_ID="${1:?Usage: ./deploy.sh <project-id> [environment]}"
ENVIRONMENT="${2:-staging}"
REGION="asia-south1"
REPO="${REGION}-docker.pkg.dev/${PROJECT_ID}/kms"
TAG="$(date +%Y%m%d-%H%M%S)"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

echo "==> Project:     ${PROJECT_ID}"
echo "==> Environment: ${ENVIRONMENT}"
echo "==> Tag:         ${TAG}"
echo

# The frontend needs the API URL at build time (NEXT_PUBLIC_* is inlined), so
# resolve it from the already-deployed API service before building.
echo "==> Resolving API URL"
API_URL="$(gcloud run services describe "kms-${ENVIRONMENT}-api" \
  --project "${PROJECT_ID}" --region "${REGION}" \
  --format 'value(status.url)' 2>/dev/null || echo "")"

if [[ -z "${API_URL}" ]]; then
  echo "    API service not found yet — building frontend without an API URL."
  echo "    Re-run this script after the first deploy to wire them together."
fi

echo "==> Building backend image"
gcloud builds submit "${ROOT}/backend" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --tag "${REPO}/api:${TAG}"

echo "==> Building frontend image"
gcloud builds submit "${ROOT}/frontend" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --tag "${REPO}/web:${TAG}" \
  --substitutions "_API_URL=${API_URL}"

echo "==> Deploying backend"
gcloud run deploy "kms-${ENVIRONMENT}-api" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --image "${REPO}/api:${TAG}" \
  --quiet

echo "==> Deploying frontend"
gcloud run deploy "kms-${ENVIRONMENT}-web" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --image "${REPO}/web:${TAG}" \
  --quiet

echo
echo "==> Deployed."
gcloud run services list \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --format 'table(metadata.name, status.url)'
