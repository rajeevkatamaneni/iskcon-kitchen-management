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

  # Deliberately NOT setting user_project_override here, unlike the bootstrap
  # layer. That override is required for billingbudgets, but Service Networking's
  # VPC peering call rejects the extra X-Goog-User-Project header it adds and
  # fails with an UNAUTHENTICATED error. Nothing in this layer needs it.
}

locals {
  runtime_sa = "kms-app-runtime@${var.project_id}.iam.gserviceaccount.com"
  image_repo = "${var.region}-docker.pkg.dev/${var.project_id}/kms"

  # Cloud Run refuses to create a service whose image can't be pulled, and on a
  # fresh environment nothing has been built yet. Create against Google's public
  # hello image, then deploy.sh replaces it with the real one. The lifecycle
  # ignore_changes on each service stops a later `terraform apply` from
  # reverting a deployed image back to this placeholder.
  placeholder_image = "us-docker.pkg.dev/cloudrun/container/hello"

  api_image = var.api_image != "" ? var.api_image : local.placeholder_image
  web_image = var.web_image != "" ? var.web_image : local.placeholder_image
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

    # Cloud SQL now defaults new instances to ENTERPRISE_PLUS, which rejects
    # shared-core tiers (db-f1-micro, db-g1-small). ENTERPRISE is the edition
    # that supports them and is the right fit at pilot scale — ENTERPRISE_PLUS
    # buys performance features we don't need and can't afford yet.
    edition = var.db_edition

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

# The migration role's password. The application role is denied DDL on purpose, which only holds if
# something else owns the schema and runs the migrations — so the services need both credentials.
resource "google_secret_manager_secret" "db_migration_password" {
  secret_id = "kms-${var.environment}-db-migration-password"
  replication {
    user_managed {
      replicas {
        location = var.region
      }
    }
  }
}

resource "google_secret_manager_secret_version" "db_migration_password" {
  secret      = google_secret_manager_secret.db_migration_password.id
  secret_data = random_password.db_migration_user.result
}

# ---------------------------------------------------------------------------
# A temple's own payment credentials
#
# These are not created here, because they do not exist until a temple administrator types them
# into Settings. What Terraform grants is the ability to create them — and only them.
#
# The naming is what makes that possible: every one is kms-{env}-tenant-{uuid}-*, derived from the
# tenant id rather than stored, so the condition below can bound the grant by prefix. Without the
# condition the runtime would need secretmanager.admin over the whole project, which would put the
# database passwords sitting beside these within reach of a web-facing process.
# ---------------------------------------------------------------------------
resource "google_project_iam_custom_role" "tenant_secret_manager" {
  role_id     = "kmsTenantSecrets${title(var.environment)}"
  title       = "KMS tenant secrets (${var.environment})"
  description = "Create, read and erase the secrets a temple owns. Nothing else in Secret Manager."
  permissions = [
    "secretmanager.secrets.create",
    "secretmanager.secrets.get",
    "secretmanager.secrets.delete",
    "secretmanager.versions.add",
    "secretmanager.versions.access",
    "secretmanager.versions.get",
  ]
}

resource "google_project_iam_member" "runtime_tenant_secrets" {
  project = var.project_id
  role    = google_project_iam_custom_role.tenant_secret_manager.id
  member  = "serviceAccount:${local.runtime_sa}"

  # The whole point. Bounded to this environment's tenant secrets, so a compromise of the
  # application cannot reach kms-*-db-app-password, which lives in the same project.
  condition {
    title       = "Only this environment's tenant secrets"
    description = "kms-${var.environment}-tenant-* and nothing else"
    expression  = "resource.name.startsWith(\"projects/${var.project_id}/secrets/kms-${var.environment}-tenant-\")"
  }
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
      # Cloud Run treats min_instance_count = 0 as unset and omits it from API
      # responses, so passing a literal 0 produces a permanent plan diff and
      # makes `terraform plan` useless as a drift check. Send null instead.
      min_instance_count = var.api_min_instances > 0 ? var.api_min_instances : null
      max_instance_count = var.max_instances
    }

    vpc_access {
      connector = google_vpc_access_connector.main.id
      egress    = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = local.api_image

      resources {
        limits = {
          cpu = "1"
          # Headless Chromium renders every PDF in this image, and a browser plus a JVM does not
          # fit in a gigabyte — the worker was killed mid-render at 1062 MiB.
          memory = "2Gi"
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
      # Migrations run as the schema owner, never as the application role.
      env {
        name  = "DB_MIGRATION_USER"
        value = google_sql_user.migration.name
      }
      env {
        name = "DB_MIGRATION_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_migration_password.secret_id
            version = "latest"
          }
        }
      }
      env {
        name  = "DOCUMENTS_BUCKET"
        value = google_storage_bucket.documents.name
      }
      # Documents are written by the worker and downloaded through the API — two
      # separate containers, so the store has to be somewhere both can reach.
      # The default is a local temp directory, which meant every download 404'd.
      env {
        name  = "DOCUMENTS_STORAGE"
        value = "gcs"
      }
      # Headless Chromium, bundled in the image. The default stub renderer emits a
      # placeholder no PDF reader will open.
      env {
        name  = "DOCUMENTS_RENDERER"
        value = "playwright"
      }
      env {
        name  = "TRANSLATION_PROVIDER"
        value = "google"
      }
      # Turning a typed place into coordinates so temples can be offered by distance. One lookup per
      # registration, which is why OpenStreetMap's free service is enough; its policy asks to be told
      # who is calling.
      env {
        name  = "GEOCODING_PROVIDER"
        value = "nominatim"
      }
      env {
        name  = "NOMINATIM_USER_AGENT"
        value = "ISKCON-KMS/1.0 (${var.environment}; temple kitchen management; +https://github.com/rajeevkatamaneni/iskcon-kitchen-management)"
      }
      # Cloud Translation addresses a project explicitly. Unset, the request went out as
      # "projects//locations/global" and every translation came back INVALID_ARGUMENT.
      env {
        name  = "GCP_PROJECT_ID"
        value = var.project_id
      }
      # A temple's own payment credentials live in Secret Manager, never in our schema. On the
      # default in-memory store a saved key would survive only until this container is replaced —
      # which looks like working, right up until the next deploy.
      env {
        name  = "SECRETS_STORE"
        value = "gcp"
      }
      env {
        name  = "SECRETS_PROJECT_ID"
        value = var.project_id
      }
      env {
        name  = "KMS_ENVIRONMENT"
        value = var.environment
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.environment
      }

      # The browser calls the API cross-origin from the web app, so the backend must
      # allow that exact origin (never a wildcard). Set per environment in tfvars —
      # can't reference the frontend service here (it already depends on the API, which
      # would be a cycle).
      env {
        name  = "CORS_ALLOWED_ORIGINS"
        value = var.cors_allowed_origins
      }

      # Turn on real Firebase token verification. Without this the backend runs the
      # RejectingTokenVerifier and 401s every request. Credentials come from the runtime
      # service account (ADC); the audience is checked against FIREBASE_PROJECT_ID, which
      # defaults to the separate Firebase Auth project (iskcon-kms-2026-620ee).
      env {
        name  = "KMS_FIREBASE_ENABLED"
        value = "true"
      }

      startup_probe {
        tcp_socket {
          port = 8080
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

  # deploy.sh owns which image is live; Terraform owns everything else.
  lifecycle {
    ignore_changes = [
      # deploy.sh owns which image is live; Terraform owns everything else.
      template[0].containers[0].image,
      client,
      client_version,
      # NOTE: min_instance_count is deliberately NOT ignored. It used to be,
      # because Cloud Run treats a count of 0 as unset and omits it from API
      # responses while the provider still records 0 in state — a diff that
      # never converges. That only bites at 0; both services now run at a
      # managed non-zero minimum, and pinning them is a decision we want
      # `terraform plan` to show. If either is ever set back to 0, expect the
      # perpetual diff to return and suppress it again here.
      #
    ]
  }
}

# The background worker: the same image as the API, with the scheduler switched on.
#
# It exists as its own service rather than as a flag on the API for reasons that only bite once
# there is load. Every API instance with the scheduler on joins the Quartz cluster and pulls jobs,
# so job capacity would track *web traffic* — ten runners during a festival rush, none at 3am.
# Keeping jobs here also keeps them off the request path: document generation drives headless
# Chromium, and a render competing for the API's 1 GiB is how the tier temple staff actually touch
# runs out of memory. And this is the tier that must never scale to zero, which would otherwise
# force the larger, public, request-serving service to be always-on instead of this one.
#
# It serves no traffic: Cloud Run requires a container listening on $PORT, so it boots the same web
# stack, but ingress is internal and nothing routes to it. Firebase verification stays off — it
# authenticates nobody — and CORS is absent for the same reason.
resource "google_cloud_run_v2_service" "worker" {
  name     = "kms-${var.environment}-worker"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_INTERNAL_ONLY"

  template {
    service_account = local.runtime_sa

    scaling {
      # Always one, never more than one for now. Quartz clustering makes several safe — each trigger
      # still fires exactly once — but at pilot volume a second instance buys nothing and doubles
      # the standing cost. Raise this when a job queue actually backs up, not before.
      min_instance_count = 1
      max_instance_count = 1
    }

    vpc_access {
      connector = google_vpc_access_connector.main.id
      egress    = "PRIVATE_RANGES_ONLY"
    }

    containers {
      image = local.api_image

      resources {
        limits = {
          cpu = "1"
          # As the API: a JVM and a headless browser together need more than a gigabyte.
          memory = "2Gi"
        }
        # Billed for a running instance rather than per request, since this one is always up and
        # must have CPU between requests — there are no requests.
        cpu_idle = false
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
      # Migrations run as the schema owner, never as the application role.
      env {
        name  = "DB_MIGRATION_USER"
        value = google_sql_user.migration.name
      }
      env {
        name = "DB_MIGRATION_PASSWORD"
        value_source {
          secret_key_ref {
            secret  = google_secret_manager_secret.db_migration_password.secret_id
            version = "latest"
          }
        }
      }
      env {
        name  = "DOCUMENTS_BUCKET"
        value = google_storage_bucket.documents.name
      }
      # Documents are written by the worker and downloaded through the API — two
      # separate containers, so the store has to be somewhere both can reach.
      # The default is a local temp directory, which meant every download 404'd.
      env {
        name  = "DOCUMENTS_STORAGE"
        value = "gcs"
      }
      # Headless Chromium, bundled in the image. The default stub renderer emits a
      # placeholder no PDF reader will open.
      env {
        name  = "DOCUMENTS_RENDERER"
        value = "playwright"
      }
      env {
        name  = "TRANSLATION_PROVIDER"
        value = "google"
      }
      # Turning a typed place into coordinates so temples can be offered by distance. One lookup per
      # registration, which is why OpenStreetMap's free service is enough; its policy asks to be told
      # who is calling.
      env {
        name  = "GEOCODING_PROVIDER"
        value = "nominatim"
      }
      env {
        name  = "NOMINATIM_USER_AGENT"
        value = "ISKCON-KMS/1.0 (${var.environment}; temple kitchen management; +https://github.com/rajeevkatamaneni/iskcon-kitchen-management)"
      }
      # Cloud Translation addresses a project explicitly. Unset, the request went out as
      # "projects//locations/global" and every translation came back INVALID_ARGUMENT.
      env {
        name  = "GCP_PROJECT_ID"
        value = var.project_id
      }
      # A temple's own payment credentials live in Secret Manager, never in our schema. On the
      # default in-memory store a saved key would survive only until this container is replaced —
      # which looks like working, right up until the next deploy.
      env {
        name  = "SECRETS_STORE"
        value = "gcp"
      }
      env {
        name  = "SECRETS_PROJECT_ID"
        value = var.project_id
      }
      env {
        name  = "KMS_ENVIRONMENT"
        value = var.environment
      }
      env {
        name  = "SPRING_PROFILES_ACTIVE"
        value = var.environment
      }

      # The one line that distinguishes this service from the API: it fires triggers.
      env {
        name  = "KMS_WORKER_ENABLED"
        value = "true"
      }

      startup_probe {
        tcp_socket {
          port = 8080
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

  lifecycle {
    ignore_changes = [
      # deploy.sh owns which image is live; Terraform owns everything else.
      template[0].containers[0].image,
      client,
      client_version,
    ]
  }
}

resource "google_cloud_run_v2_service" "frontend" {
  name     = "kms-${var.environment}-web"
  location = var.region
  ingress  = "INGRESS_TRAFFIC_ALL"

  template {
    service_account = local.runtime_sa

    scaling {
      # Cloud Run treats min_instance_count = 0 as unset and omits it from API
      # responses, so passing a literal 0 produces a permanent plan diff and
      # makes `terraform plan` useless as a drift check. Send null instead.
      min_instance_count = var.web_min_instances > 0 ? var.web_min_instances : null
      max_instance_count = var.max_instances
    }

    containers {
      image = local.web_image

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

  lifecycle {
    ignore_changes = [
      # deploy.sh owns which image is live; Terraform owns everything else.
      template[0].containers[0].image,
      client,
      client_version,
      # NOTE: min_instance_count is deliberately NOT ignored. It used to be,
      # because Cloud Run treats a count of 0 as unset and omits it from API
      # responses while the provider still records 0 in state — a diff that
      # never converges. That only bites at 0; both services now run at a
      # managed non-zero minimum, and pinning them is a decision we want
      # `terraform plan` to show. If either is ever set back to 0, expect the
      # perpetual diff to return and suppress it again here.
      #
    ]
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
