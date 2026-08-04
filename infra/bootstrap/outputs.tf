output "state_bucket" {
  description = "GCS bucket holding Terraform state. Pass to the environment layer's -backend-config."
  value       = google_storage_bucket.tf_state.name
}

output "artifact_registry" {
  description = "Docker repository for application images."
  value       = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.images.repository_id}"
}

output "app_runtime_service_account" {
  description = "Service account the Cloud Run services run as."
  value       = google_service_account.app_runtime.email
}
