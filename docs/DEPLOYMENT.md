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

**One secret must already exist.** Both Cloud Run services mount `kms-<env>-maps-api-key` for Places, Static Maps and Routes (see *Google Maps Platform* below). Terraform **reads** that secret rather than creating it — the key itself must never reach Terraform state or this repository — so on a brand-new environment create it, give it a version and grant the runtime service account access **before** this apply. Otherwise the plan stops with *secret not found*:

```bash
gcloud secrets create kms-staging-maps-api-key \
  --project <your-project-id> --replication-policy automatic
printf '%s' '<the restricted Maps Platform key>' | \
  gcloud secrets versions add kms-staging-maps-api-key \
    --project <your-project-id> --data-file=-
gcloud secrets add-iam-policy-binding kms-staging-maps-api-key \
  --project <your-project-id> \
  --member "serviceAccount:kms-app-runtime@<your-project-id>.iam.gserviceaccount.com" \
  --role roles/secretmanager.secretAccessor
```

`staging`'s secret was created this way on 2026-09-05. Bringing it under Terraform like the SMTP password would mean importing the existing secret into state; that decision is open.

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

## Google Maps Platform — deploy config

Three shipped features share one Maps key: **address suggestions** while typing a delivery
address (Places — proxied through the API on purpose, so no Maps key ever reaches a browser
bundle), the **map pin** on a job card's delivery sheet (Static Maps), and **when to leave
the temple** for a delivered event (Routes, E4-S16).

All three are off by default and the application is hermetic without them. A temple with no
map service plans a delivery in exactly the same number of clicks: the address stays a plain
text box, the delivery sheet prints the address with no picture, and the planner shows one
quiet line saying the estimate is unavailable. None of that is a fault — a map service the
temple does not have must never stand between a cook and a meal plan. The test suite needs
neither service nor credentials.

**Terraform sets all six variables. Do not set any of them by hand.** They are in
`infra/environment/main.tf`, on the **API and the worker**, because both run the same image
and neither should behave differently from the other by accident.

| Variable | Value | Shape |
|---|---|---|
| `PLACES_PROVIDER` | `google` | literal |
| `STATIC_MAP_PROVIDER` | `google` | literal |
| `TRAVEL_TIME_PROVIDER` | `google-routes` | literal |
| `PLACES_API_KEY` | the Maps key | Secret Manager — `kms-<env>-maps-api-key`, version `latest` |
| `STATIC_MAP_API_KEY` | the Maps key | the same secret |
| `ROUTES_API_KEY` | the Maps key | the same secret |

*Until 2026-09-07 these six were set with `gcloud run services update` and were absent from
Terraform, so Step 2 of this very runbook — `terraform apply` — would have deleted all six
and turned three shipped features silently back off. A value set with `gcloud` survives only
until the next apply, and its disappearance looks like a bug in the feature rather than in
the deploy. If one of these has to change, change it in `main.tf`.*

APIs, enabled once per project:

```bash
gcloud services enable routes.googleapis.com              --project iskcon-kms-2026
gcloud services enable places.googleapis.com              --project iskcon-kms-2026
gcloud services enable static-maps-backend.googleapis.com --project iskcon-kms-2026
gcloud services enable geocoding-backend.googleapis.com   --project iskcon-kms-2026
```

Geocoding — turning a typed place into coordinates — is a separate switch with its own
provider and is not paid for by the Maps key:

```bash
GEOCODING_PROVIDER=nominatim   # or a Google geocoder, if the key route is taken
```

**Credentials — one restricted key in Secret Manager.** An earlier version of this section
planned to leave `ROUTES_API_KEY` unset so the provider would authenticate as the Cloud Run
service account (Application Default Credentials): no secret to store, none to rotate, and
none of the static-egress-IP machinery an IP-restricted key demands. That is still what
`application.yml` documents an empty value as meaning, and it is still the better shape where
it works — but it is **not what is deployed**, because Static Maps has no OAuth form at all:
it is a signed GET taking a key and nothing else. The environment therefore needs a key
regardless, and one key restricted to three APIs is one credential to rotate instead of two.
So all three keys read the same secret. Restrict the key to Places, Static Maps and Routes in
the console, and grant it nothing else.

**Quotas, not a budget.** A billing budget alerts and does **not** cap spend; per-API
daily quotas do, and are set in the console under *APIs & Services → Quotas*:

| API | Daily quota | Why |
|---|---|---|
| Routes | 200 requests | Two calls per estimate (OPTIMISTIC, PESSIMISTIC), a few dozen deliveries a day |
| Geocoding | 50 requests | One per delivery address, and only when the address changes or its coordinates pass thirty days |

At India pricing the expected bill is **zero**: traffic-aware routing bills as the Pro SKU
with 35,000 free calls a month, against about 1,860 used at thirty deliveries a day. A
runaway loop fails closed at a few cents rather than running up a bill overnight.

**Places and Static Maps have no agreed daily quota yet, and should.** Both are now enabled
in `staging` and neither is capped. Places autocomplete fires per keystroke (debounced) rather
than per delivery, so its ceiling is not a number this section can guess from the delivery
count the way the Routes row does — it needs a measurement or a decision, not an invented
figure. **Open, and it is a cost exposure until it is closed.**

**Nothing about a drive is stored.** Maps Platform ToS §3.2.3(b) permits no caching except
where expressly allowed; the Routes clause permits latitude and longitude only. Geocoded
coordinates live on the meal plan with a thirty-day life and are looked up again after
that; durations are computed when the event is shown and kept nowhere.
**This reading should be confirmed with Google support before anyone leans on it.**
