output "api_url" {
  description = "Public URL of the API service."
  value       = google_cloud_run_v2_service.api.uri
}

output "web_url" {
  description = "Public URL of the web application."
  value       = google_cloud_run_v2_service.frontend.uri
}

output "db_instance_name" {
  description = "Cloud SQL instance name (randomised suffix — changes on every recreate)."
  value       = google_sql_database_instance.main.name
}

output "db_connection_name" {
  description = "Cloud SQL connection name, for `gcloud sql connect` and the Cloud SQL Auth Proxy."
  value       = google_sql_database_instance.main.connection_name
}

output "documents_bucket" {
  description = "GCS bucket for generated PDFs and uploads."
  value       = google_storage_bucket.documents.name
}
