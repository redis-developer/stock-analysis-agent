output "service_uri" {
  description = "Cloud Run service URL."
  value       = google_cloud_run_v2_service.app.uri
}

output "image_url" {
  description = "Artifact Registry image URL used when var.image is empty."
  value       = local.generated_image
}

output "artifact_registry_repository" {
  description = "Artifact Registry repository name."
  value       = google_artifact_registry_repository.app.name
}

output "cloud_run_service_account" {
  description = "Cloud Run runtime service account email."
  value       = google_service_account.cloud_run.email
}
