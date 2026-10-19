# ---------------------------------------------------------------------------
# API keep-warm: the API stays up for about an hour after anyone last used it.
#
# Added 2026-10-04 at Rajeev's request. The API scales to zero (api_min_instances = 0), and Cloud
# Run shuts an idle instance down after about 15 minutes; that window is Cloud Run's own and has
# no setting. He wanted an hour, so this stretches it.
#
# Every 5 minutes Cloud Scheduler runs a small Workflow. It asks Cloud Logging whether the API has
# served a request in the last 45 minutes, not counting its own pings. If so, it pings
# /actuator/health, which keeps the instance from going idle. Once nobody has used the API for 45
# minutes the pings stop, Cloud Run's own ~15 minutes runs out, and the instance goes down: about
# an hour after the last real request.
#
# It never wakes a cold API: with no real request in the window it does nothing. A browser tab
# left open on the app counts as use for as long as it keeps calling the API.
#
# Cost: the scheduler job and the workflow runs are pennies a month. The real cost is the warm
# hour itself, billed as instance time like any other.
# ---------------------------------------------------------------------------

locals {
  keep_warm_window_seconds = 45 * 60
  api_url                  = google_cloud_run_v2_service.api.uri
}

resource "google_project_service" "keep_warm" {
  for_each           = toset(["workflows.googleapis.com", "cloudscheduler.googleapis.com"])
  service            = each.value
  disable_on_destroy = false
}

resource "google_service_account" "keep_warm" {
  account_id   = "kms-${var.environment}-keep-warm"
  display_name = "KMS ${var.environment} API keep-warm (reads request logs, runs the workflow)"
}

# Read the API's request logs, and nothing else of consequence.
resource "google_project_iam_member" "keep_warm_logs" {
  project = var.project_id
  role    = "roles/logging.viewer"
  member  = "serviceAccount:${google_service_account.keep_warm.email}"
}

# Lets Cloud Scheduler start the workflow as this account.
resource "google_project_iam_member" "keep_warm_invoker" {
  project = var.project_id
  role    = "roles/workflows.invoker"
  member  = "serviceAccount:${google_service_account.keep_warm.email}"
}

resource "google_workflows_workflow" "keep_warm" {
  name            = "kms-${var.environment}-api-keep-warm"
  region          = var.region
  description     = "Pings the API while it has been used in the last 45 minutes, so it stays up about an hour after last use."
  service_account = google_service_account.keep_warm.id

  # $${...} is a Workflows expression; the doubled $ stops Terraform reading it as its own.
  source_contents = <<-YAML
    main:
      steps:
        - init:
            assign:
              - since: $${time.format(sys.now() - ${local.keep_warm_window_seconds})}
              - filter: $${"resource.type=\"cloud_run_revision\" AND resource.labels.service_name=\"${google_cloud_run_v2_service.api.name}\" AND logName=\"projects/${var.project_id}/logs/run.googleapis.com%2Frequests\" AND NOT httpRequest.requestUrl:\"/actuator/health\" AND timestamp>=\"" + since + "\""}
        - recent_requests:
            call: http.post
            args:
              url: https://logging.googleapis.com/v2/entries:list
              auth:
                type: OAuth2
              body:
                resourceNames: ["projects/${var.project_id}"]
                filter: $${filter}
                orderBy: "timestamp desc"
                pageSize: 1
            result: found
        - decide:
            switch:
              - condition: $${"entries" in found.body}
                next: ping
            next: idle
        - ping:
            call: http.get
            args:
              url: ${local.api_url}/actuator/health
              timeout: 120
            result: health
        - pinged:
            return: $${"pinged; last use " + found.body.entries[0].timestamp}
        - idle:
            return: "no use in the window; left alone"
  YAML

  depends_on = [google_project_service.keep_warm]
}

resource "google_cloud_scheduler_job" "keep_warm" {
  name      = "kms-${var.environment}-api-keep-warm"
  region    = var.region
  schedule  = "*/5 * * * *"
  time_zone = "Etc/UTC"

  http_target {
    http_method = "POST"
    uri         = "https://workflowexecutions.googleapis.com/v1/${google_workflows_workflow.keep_warm.id}/executions"
    body        = base64encode("{}")
    oauth_token {
      service_account_email = google_service_account.keep_warm.email
    }
  }

  depends_on = [google_project_service.keep_warm, google_project_iam_member.keep_warm_invoker]
}
