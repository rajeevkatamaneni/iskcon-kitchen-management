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
