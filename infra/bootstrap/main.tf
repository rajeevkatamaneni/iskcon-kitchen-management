terraform {
  required_version = ">= 1.9"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region

  # Some APIs (billingbudgets in particular) reject calls made with user
  # Application Default Credentials unless a billing/quota project is sent
  # explicitly — otherwise they attribute the call to gcloud's own default
  # client project and fail with SERVICE_DISABLED.
  billing_project       = var.project_id
  user_project_override = true
}

# ---------------------------------------------------------------------------
# APIs. Enabled once; disabling them is not part of environment teardown.
# ---------------------------------------------------------------------------
resource "google_project_service" "apis" {
  for_each = toset([
    "run.googleapis.com",
    "sqladmin.googleapis.com",
    "secretmanager.googleapis.com",
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "storage.googleapis.com",
    "logging.googleapis.com",
    "monitoring.googleapis.com",
    "iam.googleapis.com",
    "cloudresourcemanager.googleapis.com",
    "billingbudgets.googleapis.com",
    "serviceusage.googleapis.com",
    "compute.googleapis.com",
    "servicenetworking.googleapis.com",
    "vpcaccess.googleapis.com",
    # Recipe translation (E2-S6).
    "translate.googleapis.com",
  ])

  service            = each.value
  disable_on_destroy = false
}

# ---------------------------------------------------------------------------
# Terraform state. Must outlive environment teardown, hence it lives here.
# ---------------------------------------------------------------------------
resource "google_storage_bucket" "tf_state" {
  name          = "${var.project_id}-tf-state"
  location      = var.region
  force_destroy = false

  versioning {
    enabled = true
  }

  uniform_bucket_level_access = true

  lifecycle {
    prevent_destroy = true
  }

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Container images. Rebuilt rarely; keeping them survives teardown and makes
# the next spin-up fast.
# ---------------------------------------------------------------------------
resource "google_artifact_registry_repository" "images" {
  location      = var.region
  repository_id = "kms"
  description   = "Container images for the Kitchen Management System"
  format        = "DOCKER"

  depends_on = [google_project_service.apis]
}

# ---------------------------------------------------------------------------
# Runtime service account. Least privilege per SYSTEM_DESIGN.md §7 — it can
# reach the database, read its own secrets, write objects and telemetry, and
# nothing else.
# ---------------------------------------------------------------------------
resource "google_service_account" "app_runtime" {
  account_id   = "kms-app-runtime"
  display_name = "KMS application runtime (Cloud Run)"

  depends_on = [google_project_service.apis]
}

resource "google_project_iam_member" "app_runtime_roles" {
  for_each = toset([
    "roles/cloudsql.client",
    "roles/secretmanager.secretAccessor",
    "roles/storage.objectAdmin",
    "roles/logging.logWriter",
    "roles/monitoring.metricWriter",
    "roles/cloudtrace.agent",
    # Recipe translation (E2-S6) via Cloud Translation v3.
    "roles/cloudtranslate.user",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.app_runtime.email}"
}

# ---------------------------------------------------------------------------
# Budget alert. E1-S2 acceptance criterion.
# ---------------------------------------------------------------------------
resource "google_billing_budget" "monthly" {
  count = var.billing_account == "" ? 0 : 1

  billing_account = var.billing_account
  display_name    = "KMS monthly budget"

  budget_filter {
    projects = ["projects/${data.google_project.current.number}"]
  }

  amount {
    specified_amount {
      currency_code = "USD"
      units         = tostring(var.budget_amount_usd)
    }
  }

  dynamic "threshold_rules" {
    for_each = [0.5, 0.9, 1.0]
    content {
      threshold_percent = threshold_rules.value
    }
  }

  depends_on = [google_project_service.apis]
}

data "google_project" "current" {
  project_id = var.project_id
}
