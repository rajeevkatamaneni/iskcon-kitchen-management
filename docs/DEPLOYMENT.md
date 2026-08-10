# Deployment Runbook

How to get the application running on Google Cloud (`asia-south1`, Mumbai).

Everything here runs on **your** machine — the development sandbox has no GCP credentials and cannot reach Google's APIs. Claude writes the configuration; you apply it.

---

## One-time prerequisites

1. **A GCP project with billing enabled.** Note the project ID.
2. **Install tooling:**
   - [gcloud CLI](https://cloud.google.com/sdk/docs/install)
   - [Terraform](https://developer.hashicorp.com/terraform/downloads) ≥ 1.9
3. **Authenticate:**
   ```bash
   gcloud auth login
   gcloud auth application-default login
   gcloud config set project <your-project-id>
   ```

---

## Step 1 — Bootstrap (once, ever)

Creates the things that must survive environment teardown: enabled APIs, the Terraform state bucket, Artifact Registry, the runtime service account, and the budget alert.

```bash
cd infra/bootstrap
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: set project_id and billing_account
terraform init
terraform apply
```

Record the `state_bucket` output — Step 2 needs it.

Find your billing account ID with `gcloud billing accounts list`.

---

## Step 2 — Environment

Creates Cloud SQL, Cloud Run services, VPC, secrets, and the documents bucket. This is the layer that is safe to destroy.

```bash
cd ../environment
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: set project_id
terraform init -backend-config="bucket=<state_bucket from Step 1>"
terraform apply
```

Expect **8–12 minutes** — Cloud SQL provisioning dominates. Cloud Run services will fail their first health check because no image exists yet; that is expected and resolves in Step 3.

---

## Step 3 — Build and deploy

```bash
cd ..
./deploy.sh <your-project-id> staging
```

Builds both images with Cloud Build, pushes to Artifact Registry, and rolls out to Cloud Run. Prints the service URLs when done.

Run it **twice on the very first deploy**: the frontend inlines the API URL at build time, and that URL doesn't exist until the API has been deployed once. The script tells you when this applies.

---

## Step 4 — Verify

```bash
API_URL=$(gcloud run services describe kms-staging-api --region asia-south1 --format 'value(status.url)')
curl "$API_URL/health"
```

Expect `{"status":"UP","timestamp":"..."}`.

Then open the web URL in a browser.

Share both URLs in the chat and Claude will verify the deployment directly — checking endpoint behaviour, and browsing the UI once there are screens to look at.

---

## Step 5 — Seed the first platform operator (once per environment)

A fresh installation has no platform super-admin, and one cannot be created through the app — minting a platform operator is deliberately a privileged, out-of-band act (E1-S13). The application role (`kms_app`) cannot insert a tenantless row; only the Cloud SQL admin connection, which bypasses RLS, can. So the first operator is seeded by hand, once, and then claims the account by signing in.

Connect to Cloud SQL as the admin user (e.g. `gcloud sql connect kms-staging --user=postgres`, or via the Cloud SQL Studio), then insert a pending row for the operator's **verified** email and phone:

```sql
INSERT INTO users (tenant_id, firebase_uid, full_name, email, phone, role)
VALUES (NULL, 'pending:' || gen_random_uuid(), 'Platform Operator',
        'operator@example.com', '+919876543210', 'SUPER_ADMIN');
```

The operator then signs in with that exact email (or phone) via the web app. First sign-in binds their real Firebase identity to this row (the claim, E1-S6/E1-S13) and they land on the platform console. Adding a further operator later is the same insert.

Notes:
- The email must be one the operator will verify with Firebase (Google sign-in, or email/password with a verified address); an unverified email cannot claim the row.
- This works locally too, but local development connects as a superuser that bypasses RLS, so the write escape isn't exercised there — the production path is what the tests cover.

---

## Tearing down

```bash
cd infra/environment
terraform destroy
```

Leaves `bootstrap/` intact, so the next spin-up reuses the state bucket, images, and service account. Cost while destroyed is effectively zero.

**Before tearing down an environment that holds real data**, set `keep_alive = true` in `terraform.tfvars` — that enables Cloud SQL deletion protection and stops Terraform from removing the documents bucket.

---

## Firebase — two things to do before launch

Firebase Authentication lives in **`iskcon-kms-2026-620ee`**, a different project from the main `iskcon-kms-2026`. Firebase could not reuse the name because the GCP project already held it. Token verification is unaffected — the backend validates against Google's public keys and checks the audience claim — but two consequences need handling before a temple goes live.

**1. SMS quota is 10 per day.** Billing is attached to the GCP project, not the Firebase one. Ten messages is fine for development, and nowhere near enough for a temple onboarding volunteers. Link billing to `iskcon-kms-2026-620ee` in the Firebase console under Usage and billing.

**2. The Cloud Run service account needs access to the Firebase project.** We verify tokens with `checkRevoked=true`, so a disabled account loses access on its next request rather than when its token expires. That check calls the Firebase Auth API, which is cross-project here:

```bash
gcloud projects add-iam-policy-binding iskcon-kms-2026-620ee \
  --member="serviceAccount:kms-app-runtime@iskcon-kms-2026.iam.gserviceaccount.com" \
  --role="roles/firebaseauth.viewer"
```

Without it, the backend starts and ordinary verification works, but the revocation check fails — so a disabled user would keep access until their token expired. Worth doing.

**3. Authorized domains.** Phone sign-in only works on domains Firebase knows about. `localhost` is allowed by default; add the Cloud Run URL under Authentication → Settings → Authorized domains before testing on the deployed app.

---

## Notes and gotchas

**Cloud SQL instance names are randomised.** Google reserves the name of a deleted instance (between one week and two months, per inconsistent documentation), so a fixed name would break the second spin-up after a teardown. Each `terraform apply` generates a fresh suffix. The consequence is that database contents do not survive a teardown — fine while building, not fine once real temple data exists.

**Database data is disposable at this stage.** Schema is recreated by migrations on boot. Once there is data worth keeping, stop tearing down.

**Cold starts.** `min_instances = 0` (the build-phase default) means the first request after idle takes several seconds while a container starts. Set `min_instances = 1` for the pilot.

**Cost while running.** Roughly $25–40/month prorated on the build-phase defaults, so a working session costs well under a dollar. `terraform destroy` when finished for the day.

## Recipe documents (E2-S5) — deploy config

The document pipeline (recipe PDFs) is behind ports; deployed environments set:

```bash
DOCUMENTS_STORAGE=gcs
DOCUMENTS_BUCKET=<project>-kms-<env>-docs     # the environment/ documents bucket
DOCUMENTS_RENDERER=playwright                  # real Chromium render
GCP_PROJECT_ID=<project>
```

`DOCUMENTS_RENDERER=playwright` requires **headless Chromium + Noto fonts** (incl.
Devanagari and Kannada for E2-S6) in the **worker** image — the recipe card's font
stack names them. Without them, set `DOCUMENTS_RENDERER=stub`. Downloads are served
by an authorized backend endpoint (`/api/v1/documents/{id}/download`), not public or
signed URLs, so a temple's documents stay behind its access control.

Local dev: `DOCUMENTS_STORAGE=gcs` + `DOCUMENTS_BUCKET=<project>-kms-dev-docs`
(from `infra/dev-bucket.sh`) uses real GCS via your ADC; the renderer stays `stub`
unless you install Playwright's Chromium locally. Automated tests always use the
stub + local storage, so the suite is hermetic.
