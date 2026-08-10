# Infrastructure

Terraform for the ISKCON Kitchen Management System on Google Cloud (`asia-south1`, Mumbai), per `docs/SYSTEM_DESIGN.md` and `docs/TECH_STACK.md`.

## Two layers, on purpose

The build workflow spins the environment **up and down per working session** to avoid paying for idle Cloud SQL. That only works safely if the things that must survive teardown are separated from the things that get destroyed.

| Layer | Contents | Lifecycle |
|---|---|---|
| `bootstrap/` | Enabled APIs, Terraform state bucket, Artifact Registry, service accounts, budget alert | **Created once. Never destroyed.** |
| `environment/` | Cloud SQL, Cloud Run services, secrets, IAM bindings | Spun up and torn down per session |

Running `terraform destroy` in `environment/` is safe. Running it in `bootstrap/` would delete your Terraform state and container images — don't.

## Two gotchas this design already handles

**Cloud SQL name reuse.** Google reserves the name of a deleted Cloud SQL instance (documentation is inconsistent — between one week and two months). Recreating with the same name fails. `environment/` therefore appends a random suffix to the instance name on every apply, so each spin-up gets a fresh identity. The database *contents* do not survive teardown — see below.

**Data does not survive teardown.** By design at this stage: there is no production data yet. Schema is recreated by Flyway migrations on boot, and `environment/seed.sh` loads a demo tenant so the app is usable within a minute of spin-up. Before any real pilot data exists, switch to a persistent environment (`keep_alive = true` in `environment/terraform.tfvars`) — do not tear down an environment holding real temple data.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/downloads) ≥ 1.9
- [gcloud CLI](https://cloud.google.com/sdk/docs/install), authenticated: `gcloud auth application-default login`
- A GCP project with billing enabled

## First-time setup (once, ever)

```bash
cd infra/bootstrap
cp terraform.tfvars.example terraform.tfvars   # fill in project_id, billing_account
terraform init
terraform apply
```

Note the `state_bucket` output — the environment layer uses it.

## Per-session spin up

```bash
cd infra/environment
terraform init -backend-config="bucket=<state_bucket from bootstrap>"
terraform apply
```

Outputs the Cloud Run URLs. Roughly 8–12 minutes on first apply (Cloud SQL provisioning dominates).

## Per-session tear down

```bash
cd infra/environment
terraform destroy
```

Leaves `bootstrap/` untouched, so the next spin-up reuses your state bucket, images, and service accounts.

## Cost

With `environment/` destroyed, ongoing cost is effectively zero (state bucket and Artifact Registry storage only — cents per month). While running, the default single-zone configuration is roughly $25–40/month prorated — so a working session costs well under a dollar.

## Local development bucket (out-of-band)

`dev-bucket.sh` creates a per-developer GCS documents bucket
(`<project>-kms-dev-docs`) so recipe-PDF work (E2-S5) develops against real GCS,
not a fake. It is the cloud counterpart to `docker-compose.yml` and is
deliberately **not** in `environment/` — that layer's documents bucket is
per-deployed-environment, and sharing its Terraform state would clobber it.

```bash
./infra/dev-bucket.sh
export DOCUMENTS_BUCKET=iskcon-kms-2026-kms-dev-docs   # for the local backend
```

Automated tests do **not** touch GCS — they run against the in-process storage
stub, so the suite stays hermetic and offline (CI has no GCP credentials).
