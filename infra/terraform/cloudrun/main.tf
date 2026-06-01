terraform {
  required_version = ">= 1.5.0"

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
}

locals {
  labels = {
    app           = "stock_analysis_agent"
    owner         = "raphael_delio"
    skip_deletion = "yes"
  }

  required_services = toset([
    "artifactregistry.googleapis.com",
    "cloudbuild.googleapis.com",
    "run.googleapis.com",
    "secretmanager.googleapis.com"
  ])

  generated_image = "${var.region}-docker.pkg.dev/${var.project_id}/${google_artifact_registry_repository.app.repository_id}/${var.image_name}:${var.image_tag}"
  container_image = var.image == "" ? local.generated_image : var.image

  plain_environment = {
    STOCK_ANALYSIS_AGENT_REDIS_HOST            = var.redis_host
    STOCK_ANALYSIS_AGENT_REDIS_PORT            = tostring(var.redis_port)
    STOCK_ANALYSIS_AGENT_REDIS_USERNAME        = var.redis_username
    STOCK_ANALYSIS_AGENT_LANGCACHE_ENDPOINT    = var.langcache_url
    STOCK_ANALYSIS_AGENT_LANGCACHE_CACHE_ID    = var.langcache_cache_id
    STOCK_ANALYSIS_AGENT_AGENT_MEMORY_ENDPOINT = var.agent_memory_endpoint
    STOCK_ANALYSIS_AGENT_AGENT_MEMORY_STORE_ID = var.agent_memory_store_id
    SEC_USER_AGENT                             = var.sec_user_agent
    SPRING_MVC_ASYNC_REQUEST_TIMEOUT           = var.spring_mvc_async_request_timeout
  }

  environment = merge(
    { for key, value in local.plain_environment : key => value if value != "" },
    var.environment_variables
  )

  secret_prefix = "STOCK_ANALYSIS_AGENT_"

  secret_environment = {
    openai_api_key = {
      env_name  = "${local.secret_prefix}OPENAI_API_KEY"
      secret_id = "${var.service_name}-openai-api-key"
    }
    redis_password = {
      env_name  = "${local.secret_prefix}REDIS_PASSWORD"
      secret_id = "${var.service_name}-redis-password"
    }
    langcache_api_key = {
      env_name  = "${local.secret_prefix}LANGCACHE_API_KEY"
      secret_id = "${var.service_name}-langcache-api-key"
    }
    agent_memory_api_key = {
      env_name  = "${local.secret_prefix}AGENT_MEMORY_API_KEY"
      secret_id = "${var.service_name}-agent-memory-api-key"
    }
    twelve_data_api_key = {
      env_name  = "${local.secret_prefix}TWELVE_DATA_API_KEY"
      secret_id = "${var.service_name}-twelve-data-api-key"
    }
    tavily_api_key = {
      env_name  = "${local.secret_prefix}TAVILY_API_KEY"
      secret_id = "${var.service_name}-tavily-api-key"
    }
  }
}

resource "google_project_service" "required" {
  for_each = local.required_services

  project            = var.project_id
  service            = each.value
  disable_on_destroy = false
}

resource "google_artifact_registry_repository" "app" {
  project       = var.project_id
  location      = var.region
  repository_id = var.artifact_registry_repository_id
  description   = "Docker images for ${var.service_name}"
  format        = "DOCKER"
  labels        = local.labels

  depends_on = [
    google_project_service.required
  ]
}

resource "google_service_account" "cloud_run" {
  project      = var.project_id
  account_id   = var.service_account_id
  display_name = "Cloud Run runtime for ${var.service_name}"

  depends_on = [
    google_project_service.required
  ]
}

resource "google_secret_manager_secret" "app" {
  for_each = local.secret_environment

  project   = var.project_id
  secret_id = each.value.secret_id
  labels    = local.labels

  lifecycle {
    prevent_destroy = true
  }

  replication {
    auto {}
  }

  depends_on = [
    google_project_service.required
  ]
}

resource "google_secret_manager_secret_iam_member" "cloud_run_secret_accessor" {
  for_each = google_secret_manager_secret.app

  project   = var.project_id
  secret_id = each.value.secret_id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.cloud_run.email}"
}

resource "google_cloud_run_v2_service" "app" {
  project             = var.project_id
  name                = var.service_name
  location            = var.region
  ingress             = var.ingress
  labels              = local.labels
  deletion_protection = var.deletion_protection

  scaling {
    min_instance_count = var.min_instance_count
    scaling_mode       = "AUTOMATIC"
  }

  template {
    labels                           = local.labels
    service_account                  = google_service_account.cloud_run.email
    timeout                          = "${var.request_timeout_seconds}s"
    max_instance_request_concurrency = var.max_instance_request_concurrency

    scaling {
      min_instance_count = var.min_instance_count
      max_instance_count = var.max_instance_count
    }

    dynamic "vpc_access" {
      for_each = var.vpc_connector == "" ? [] : [var.vpc_connector]

      content {
        connector = vpc_access.value
        egress    = var.vpc_egress
      }
    }

    containers {
      image = local.container_image

      ports {
        container_port = var.container_port
      }

      resources {
        limits = {
          cpu    = var.cpu
          memory = var.memory
        }
      }

      dynamic "env" {
        for_each = local.environment

        content {
          name  = env.key
          value = env.value
        }
      }

      dynamic "env" {
        for_each = local.secret_environment

        content {
          name = env.value.env_name

          value_source {
            secret_key_ref {
              secret  = google_secret_manager_secret.app[env.key].secret_id
              version = "latest"
            }
          }
        }
      }
    }
  }

  traffic {
    type    = "TRAFFIC_TARGET_ALLOCATION_TYPE_LATEST"
    percent = 100
  }

  depends_on = [
    google_project_service.required,
    google_secret_manager_secret.app,
    google_secret_manager_secret_iam_member.cloud_run_secret_accessor
  ]
}

resource "google_cloud_run_v2_service_iam_member" "public_invoker" {
  count = var.allow_unauthenticated ? 1 : 0

  project  = var.project_id
  location = google_cloud_run_v2_service.app.location
  name     = google_cloud_run_v2_service.app.name
  role     = "roles/run.invoker"
  member   = "allUsers"
}
