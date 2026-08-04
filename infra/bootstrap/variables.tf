variable "project_id" {
  description = "GCP project ID"
  type        = string
}

variable "region" {
  description = "GCP region. asia-south1 (Mumbai) per TECH_STACK.md — India data residency and latency."
  type        = string
  default     = "asia-south1"
}

variable "billing_account" {
  description = "Billing account ID for the budget alert. Leave empty to skip budget creation."
  type        = string
  default     = ""
}

variable "budget_amount_usd" {
  description = "Monthly budget threshold in USD. Alerts fire at 50%, 90%, and 100%."
  type        = number
  default     = 150
}
