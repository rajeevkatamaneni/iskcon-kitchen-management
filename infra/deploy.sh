#!/usr/bin/env bash
#
# Build both container images with Cloud Build, push to Artifact Registry, and
# roll them out to Cloud Run.
#
# Usage:
#   ./deploy.sh <project-id> [environment]
#
# Assumes infra/bootstrap and infra/environment have both been applied.
#
# ---------------------------------------------------------------------------
# Why this is shaped the way it is (2026-09-04, work queue item 1)
#
# A deploy that changed no dependencies took about twenty-five minutes. Four
# things were paying for that, and this script fixes three of them; the fourth
# lives in the Dockerfiles and the cloudbuild.yaml files beside them.
#
#   1. The whole source directory was uploaded. `gcloud builds submit` looks for a
#      .gcloudignore in the directory it is given — not in the repository root —
#      and neither backend/ nor frontend/ had one. So every deploy tarred, uploaded
#      and unpacked 861 MB of frontend (645 MB of it node_modules, 213 MB .next) and
#      116 MB of backend. Fixed by the .gcloudignore/.dockerignore files.
#   2. No layer cache. Cloud Build runs on a fresh VM, so `docker build` starts cold
#      unless the previous image is pulled and passed as --cache-from. Fixed in the
#      two cloudbuild.yaml files.
#   3. The two images built one after the other. They share nothing, so they now
#      build at the same time. This is safe because API_URL is resolved from the
#      *already running* api service before either build starts — a Cloud Run URL
#      is stable for the life of the service, so the frontend does not need the new
#      api revision, only the address, which it already has. The ordering the old
#      comment described was only ever true on the very first deploy.
#   4. Three serial rollouts. The api still goes first and alone, because it runs
#      the migrations and the worker must not start jobs against an unmigrated
#      schema. The worker and the web have no such relationship to each other, so
#      they now roll out together.
#
# Deliberately NOT traded away for speed: the Flyway migration check, the Cloud Run
# health check, and building each image once and deploying that same digest.
# ---------------------------------------------------------------------------

set -euo pipefail

PROJECT_ID="${1:?Usage: ./deploy.sh <project-id> [environment]}"
ENVIRONMENT="${2:-staging}"
REGION="asia-south1"
REPO="${REGION}-docker.pkg.dev/${PROJECT_ID}/kms"
TAG="$(date +%Y%m%d-%H%M%S)"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS="$(mktemp -d)"
START="$(date +%s)"

elapsed() { printf '%dm%02ds' $(( ($1) / 60 )) $(( ($1) % 60 )); }
since()   { elapsed $(( $(date +%s) - $1 )); }

echo "==> Project:     ${PROJECT_ID}"
echo "==> Environment: ${ENVIRONMENT}"
echo "==> Tag:         ${TAG}"
echo "==> Build logs:  ${LOGS}"
echo

# The frontend needs the API URL at build time (NEXT_PUBLIC_* is inlined), so
# resolve it from the already-deployed API service before building. Its value does
# not change between deploys, which is what lets the two builds run together.
echo "==> Resolving API URL"
API_URL="$(gcloud run services describe "kms-${ENVIRONMENT}-api" \
  --project "${PROJECT_ID}" --region "${REGION}" \
  --format 'value(status.url)' 2>/dev/null || echo "")"

if [[ -z "${API_URL}" ]]; then
  echo "    API service not found yet — building frontend without an API URL."
  echo "    Re-run this script after the first deploy to wire them together."
fi

# ---------------------------------------------------------------------------
# Build both images at once.
# ---------------------------------------------------------------------------
BUILD_START="$(date +%s)"
echo "==> Building both images (in parallel)"

gcloud builds submit "${ROOT}/backend" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --config "${ROOT}/backend/cloudbuild.yaml" \
  --substitutions "_IMAGE=${REPO}/api:${TAG},_IMAGE_BASE=${REPO}/api" \
  >"${LOGS}/backend.log" 2>&1 &
BACKEND_PID=$!

gcloud builds submit "${ROOT}/frontend" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --config "${ROOT}/frontend/cloudbuild.yaml" \
  --substitutions "_API_URL=${API_URL},_IMAGE=${REPO}/web:${TAG},_IMAGE_BASE=${REPO}/web" \
  >"${LOGS}/frontend.log" 2>&1 &
FRONTEND_PID=$!

BUILD_FAILED=0
wait "${BACKEND_PID}"  || { echo "!!! Backend build failed:";  tail -40 "${LOGS}/backend.log";  BUILD_FAILED=1; }
wait "${FRONTEND_PID}" || { echo "!!! Frontend build failed:"; tail -40 "${LOGS}/frontend.log"; BUILD_FAILED=1; }
[[ "${BUILD_FAILED}" -eq 0 ]] || exit 1

echo "    Both images built in $(since "${BUILD_START}"). Logs in ${LOGS}."

# ---------------------------------------------------------------------------
# Roll out. The api alone first — it migrates.
# ---------------------------------------------------------------------------
ROLLOUT_START="$(date +%s)"
echo "==> Deploying backend (alone: it runs the migrations)"
# This script sets no environment variables. It used to set API_BASE_URL here — the API's own
# address, which Terraform cannot reference from inside that service's own definition — and that
# made the variable invisible to Terraform, so `terraform apply`, Step 2 of our own runbook,
# deleted it every time and this script silently put it back on the next deploy. It is now the
# api_base_url tfvar, declared like cors_allowed_origins beside it. Terraform owns the environment;
# this script owns only which image is live.
gcloud run deploy "kms-${ENVIRONMENT}-api" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --image "${REPO}/api:${TAG}" \
  --quiet

echo "==> Deploying worker and frontend (in parallel)"
# The worker is the same image as the API — the same application with the scheduler switched on
# (KMS_WORKER_ENABLED, set by Terraform) — and goes after the API so a schema-changing release has
# already migrated before jobs start running against it. The web image carries its API address
# baked in and depends on neither at rollout time, so the two go together.
gcloud run deploy "kms-${ENVIRONMENT}-worker" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --image "${REPO}/api:${TAG}" \
  --quiet >"${LOGS}/worker-deploy.log" 2>&1 &
WORKER_PID=$!

gcloud run deploy "kms-${ENVIRONMENT}-web" \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --image "${REPO}/web:${TAG}" \
  --quiet >"${LOGS}/web-deploy.log" 2>&1 &
WEB_PID=$!

DEPLOY_FAILED=0
wait "${WORKER_PID}" || { echo "!!! Worker rollout failed:";   tail -40 "${LOGS}/worker-deploy.log"; DEPLOY_FAILED=1; }
wait "${WEB_PID}"    || { echo "!!! Frontend rollout failed:"; tail -40 "${LOGS}/web-deploy.log";    DEPLOY_FAILED=1; }
[[ "${DEPLOY_FAILED}" -eq 0 ]] || exit 1

echo
echo "==> Deployed."
gcloud run services list \
  --project "${PROJECT_ID}" \
  --region "${REGION}" \
  --format 'table(metadata.name, status.url)'

# Recorded because the work queue asks the improvement to be verified by measuring
# rather than by feel. The before figure, on 2026-09-01, was about twenty-five minutes.
echo
echo "==> Timing"
echo "    Builds:   $(elapsed $(( ROLLOUT_START - BUILD_START )))"
echo "    Rollouts: $(since "${ROLLOUT_START}")"
echo "    Total:    $(since "${START}")"
