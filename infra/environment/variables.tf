variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "GCP region"
  type        = string
  default     = "asia-south1"
}

variable "environment" {
  description = "Environment name; part of every resource name."
  type        = string
  default     = "staging"
}

variable "db_tier" {
  description = "Cloud SQL machine type. db-f1-micro is adequate while building; move to db-custom-1-3840 or larger for the pilot."
  type        = string
  default     = "db-f1-micro"
}

variable "db_edition" {
  description = "Cloud SQL edition. ENTERPRISE supports shared-core tiers (db-f1-micro); ENTERPRISE_PLUS requires larger predefined tiers."
  type        = string
  default     = "ENTERPRISE"
}

variable "db_high_availability" {
  description = "REGIONAL (multi-zone) Cloud SQL. False while building to control cost; true for the pilot per SYSTEM_DESIGN.md §8."
  type        = bool
  default     = false
}

variable "keep_alive" {
  description = "Set true once this environment holds data worth keeping. Enables Cloud SQL deletion protection and stops force-destroy of the documents bucket."
  type        = bool
  default     = false
}

variable "api_min_instances" {
  description = "Cloud Run minimum instances for the API. 0 scales to zero (cheapest, but a cold Spring Boot start is 10-20s, which testers report as 'the app is slow'); 1 keeps a warm instance. The worker is always 1 by design and is not covered by this."
  type        = number
  default     = 0
}

variable "web_min_instances" {
  description = "Cloud Run minimum instances for the web app. 0 scales to zero; 1 keeps a warm instance."
  type        = number
  default     = 0
}

variable "max_instances" {
  description = "Cloud Run maximum instances. Caps runaway scaling cost under an unexpected traffic spike."
  type        = number
  default     = 10
}

variable "api_image" {
  description = "Full image reference for the API. Defaults to <repo>/api:latest."
  type        = string
  default     = ""
}

variable "web_image" {
  description = "Full image reference for the frontend. Defaults to <repo>/web:latest."
  type        = string
  default     = ""
}

variable "cors_allowed_origins" {
  description = "Exact browser origin(s) the API accepts cross-origin calls from, comma-separated. Never a wildcard. Set to the deployed web app's URL(s)."
  type        = string
}
