terraform {
  required_version = ">= 1.9"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Bucket supplied at init time:
  #   terraform init -backend-config="bucket=<state_bucket from bootstrap>"
  backend "gcs" {
    prefix = "environment"
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

locals {
  runtime_sa = "kms-app-runtime@${var.project_id}.iam.gserviceaccount.com"
  image_repo = "${var.region}-docker.pkg.dev/${var.project_id}/kms"
}

# ---------------------------------------------------------------------------
# Cloud SQL
#
# The random suffix is not cosmetic: Google reserves the name of a deleted
# Cloud SQL instance (documented variously as one week to two months), so a
# fixed name would make the second spin-up fail after a teardown. Each apply
# gets a fresh name; `keep_alive` guards against destroying an instance that
# holds data worth keeping.
# ---------------------------------------------------------------------------
resource "random_id" "db_suffix" {
  byte_length = 4
}

resource "google_sql_database_instance" "main" {
  name             = "kms-${var.environment}-${random_id.db_suffix.hex}"
  database_version = "POSTGRES_16"
  region           = var.region

  # Guard rail: flip keep_alive to true in terraform.tfvars once this instance
  # holds real temple data, and Terraform will refuse to destroy it.
  deletion_protection = var.keep_alive

  settings {
    tier = var.db_tier

    # Single zone while building (cost); REGIONAL for the pilot, per
    # SYSTEM_DESIGN.md §8's 99.9% target.
    availability_type = var.db_high_availability ? "REGIONAL" : "ZONAL"

    disk_size       = 10
    disk_type       = "PD_SSD"
    disk_autoresize = true

    backup_configuration {
      enabled                        = true
      point_in_time_recovery_enabled = true
      start_time                     = "19:30" # 01:00 IST
      transaction_log_retention_days = 7
    }

    ip_configuration {
      # No public IP; Cloud Run reaches the instance over the Cloud SQL
      # connector using the runtime service account's cloudsql.client role.
      ipv4_enabled = false
      # Private Service Access network. Managed here for simplicity at pilot
      # scale; revisit if the network topology grows.
      private_network = google_compute_network.main.id
    }

    database_flags {
      name  = "cloudsql.iam_authentication"
      value = "on"
    }

    insights_config {
      query_insights_enabled = true
    }
  }

  depends_on = [google_service_networking_connection.private_vpc]
}

resource "google_sql_database" "kms" {
  name     = "kms"
  instance = google_sql_database_instance.main.name
}

resource "random_password" "db_app_user" {
  length  = 32
  special = true
}

# Application role. Deliberately NOT the superuser and NOT the migration role:
# per SYSTEM_DESIGN.md §3 the app role must have no DDL and no BYPASSRLS, so
# that a bug in application code cannot defeat tenant isolation. Grants are
# applied by Flyway migration V1, which runs as the migration role below.
resource "google_sql_user" "app" {
  name     = "kms_app"
  instance = google_sql_database_instance.main.name
  password = random_password.db_app_user.result
}

resource "random_password" "db_migration_user" {
  length  = 32
  special = true
}

resource "google_sql_user" "migration" {
  name     = "kms_migration"
  instance = google_sql_database_instance.main.name
  password = random_password.db_migration_user.result
}

# ---------------------------------------------------------------------------
# Networking for private Cloud SQL
# ---------------------------------------------------------------------------
resource "google_compute_network" "main" {
  name                    = "kms-${var.environment}-net"
  auto_create_subnetworks = true
}

resource "google_compute_global_address" "private_ip" {
  name          = "kms-${var.environment}-private-ip"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.main.id
}

resource "google_service_networking_connection" "private_vpc" {
  network                 = google_compute_network.main.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_ip.name]
}

resource "google_vpc_access_connector" "main" {
  name          = "kms-${var.environment}-vpc"
  region        = var.region
  network       = google_compute_network.main.name
  ip_cidr_range = "10.8.0.0/28"
  min_instances = 2
  max_instances = 3
}

# ---------------------------------------------------------------------------
# Secrets
# ---------------------------------------------------------------------------
resource "google_secret_manager_secret" "db_app_password" {
  secret_id = "kms-${var.environment}-db-app-password"
  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }
}

resource "google_secret_manager_secret_version" "db_app_password" {
  secret      = google_secret_manager_secret.db_app_password.id
  secret_data = random_password.db_app_user.result
}

# ---------------------------------------------------------------------------
# Object storage for generated PDFs, invoice scans, images
# ---------------------------------------------------------------------------
resource "google_storage_bucket" "documents" {
  name          = "${var.project_id}-kms-${var.environment}-docs"
  location      = var.region
  force_destroy = !var.keep_alive

  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  # Generated PDFs are reproducible from source data; expire them rather than
  # accumulating storage cost indefinitely (SYSTEM_DESIGN.md §11 cost controls).
  lifecycle_rule {
    condition {
      age            = 90
      matches_prefix = ["generated/"]
    }
    action {
      type = "Delete"
    }
  }
}

# ---------------------------------------------------------------------------
# Cloud Run — API, background worker, frontend
# ---------------------------------------------------------------------------
resource "google_cloud_run_v2_service" "api" {
  name     = "kms-${var.environment}-api"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = local.runtime_sa

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = 10
    }

    vpc_access {
      connector = google_vpc_access_connector.main.id
      egress    = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = var.api_image != "" ? var.api_image : "${local.image_repo}/api:latest"

      resources {
        limits = {
          cpu    = "1"
          memory = "1Gi"
        }
      }

      env {
        name  = "DB_URL"
        value = "jdbc:postgresql://${google_sql_database_instance.main.private_ip_address}:5432/kms"
      }
      env {
        name  = "DB_USER"
        value = google_sql_user.app.name
      }
      env {
        name = "DB_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_app_password.secret_id
            version = "latest"
          }
        }
      }
      env {
        name  = "DOCUMENTS_BUCKET"
        value = google_storage_bucket.documents.name
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.environment
      }

      startup_probe {
        http_get {
          path = "/health"
        }
        initial_delay_seconds = 10
        period_seconds        = 5
        failure_threshold     = 12
      }
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
}

resource "google_cloud_run_v2_service" "frontend" {
  name     = "kms-${var.environment}-web"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = local.runtime_sa

    scaling {
      min_instance_count = var.min_instances
      max_instance_count = 10
    }

    containers {
      image = var.web_image != "" ? var.web_image : "${local.image_repo}/web:latest"

      resources {
        limits = {
          cpu    = "1"
          memory = "512Mi"
        }
      }

      env {
        name  = "NEXT_PUBLIC_API_URL"
        value = google_cloud_run_v2_service.api.uri
      }
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }
}

# Public access. The application enforces authentication and tenant isolation;
# donation and wish-list pages are intentionally public per REQUIREMENTS.md.
resource "google_cloud_run_v2_service_iam_member" "api_public" {
  location = google_cloud_run_v2_service.api.location
  name     = google_cloud_run_v2_service.api.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}

resource "google_cloud_run_v2_service_iam_member" "frontend_public" {
  location = google_cloud_run_v2_service.frontend.location
  name     = google_cloud_run_v2_service.frontend.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
