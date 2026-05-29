variable "project_id" {
  description = "GCP project id."
  type        = string
}

variable "region" {
  description = "GCP region for Artifact Registry and Cloud Run."
  type        = string
  default     = "us-central1"
}

variable "service_name" {
  description = "Cloud Run service name."
  type        = string
  default     = "stock-analysis-agent"
}

variable "service_account_id" {
  description = "Cloud Run runtime service account id."
  type        = string
  default     = "stock-analysis-agent-run"
}

variable "artifact_registry_repository_id" {
  description = "Artifact Registry Docker repository id."
  type        = string
  default     = "stock-analysis-agent"
}

variable "image_name" {
  description = "Container image name inside Artifact Registry."
  type        = string
  default     = "stock-analysis-agent"
}

variable "image_tag" {
  description = "Container image tag used when image is not set."
  type        = string
  default     = "latest"
}

variable "image" {
  description = "Full container image reference. Leave empty to use the Artifact Registry image output."
  type        = string
  default     = ""
}

variable "secret_values" {
  description = "Secret values created in Secret Manager. Terraform state contains these values."
  type = object({
    openai_api_key       = string
    redis_password       = string
    langcache_api_key    = string
    agent_memory_api_key = string
    twelve_data_api_key  = string
    tavily_api_key       = string
  })
  sensitive = true
}

variable "redis_host" {
  description = "Redis 8 compatible host."
  type        = string
}

variable "redis_port" {
  description = "Redis port."
  type        = number
  default     = 6379
}

variable "redis_username" {
  description = "Redis username."
  type        = string
  default     = ""
}

variable "langcache_url" {
  description = "LangCache API base URL."
  type        = string
}

variable "langcache_cache_id" {
  description = "LangCache cache id."
  type        = string
}

variable "agent_memory_endpoint" {
  description = "Redis agent memory endpoint."
  type        = string
}

variable "agent_memory_store_id" {
  description = "Redis agent memory store id."
  type        = string
}

variable "sec_user_agent" {
  description = "SEC API user agent."
  type        = string
}

variable "spring_mvc_async_request_timeout" {
  description = "Spring MVC async request timeout."
  type        = string
  default     = "5m"
}

variable "environment_variables" {
  description = "Additional plain environment variables for Cloud Run."
  type        = map(string)
  default     = {}
}

variable "container_port" {
  description = "Container port."
  type        = number
  default     = 8080
}

variable "cpu" {
  description = "Cloud Run CPU limit."
  type        = string
  default     = "1"
}

variable "memory" {
  description = "Cloud Run memory limit."
  type        = string
  default     = "1Gi"
}

variable "min_instance_count" {
  description = "Minimum Cloud Run instances."
  type        = number
  default     = 0
}

variable "max_instance_count" {
  description = "Maximum Cloud Run instances."
  type        = number
  default     = 3
}

variable "max_instance_request_concurrency" {
  description = "Maximum concurrent requests per instance."
  type        = number
  default     = 20
}

variable "request_timeout_seconds" {
  description = "Cloud Run request timeout in seconds."
  type        = number
  default     = 300
}

variable "ingress" {
  description = "Cloud Run ingress setting."
  type        = string
  default     = "INGRESS_TRAFFIC_ALL"
}

variable "allow_unauthenticated" {
  description = "Grant allUsers the Cloud Run invoker role."
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Enable Cloud Run deletion protection."
  type        = bool
  default     = true
}

variable "vpc_connector" {
  description = "Existing Serverless VPC Access connector. Leave empty for public egress."
  type        = string
  default     = ""
}

variable "vpc_egress" {
  description = "Cloud Run VPC egress policy when vpc_connector is set."
  type        = string
  default     = "PRIVATE_RANGES_ONLY"
}
