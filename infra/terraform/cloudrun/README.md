# Cloud Run Deployment

This Terraform creates the Cloud Run service, Artifact Registry repository, Secret Manager secrets, service account, and IAM needed by the app.

It expects a Redis 8 compatible endpoint. The app uses RedisVL and semantic guardrails, so a basic Redis service without the Redis 8 module set is not enough for the default configuration.

All labelable resources use:

```text
app=stock_analysis_agent
owner=raphael_delio
skip_deletion=yes
```

Use the `app=stock_analysis_agent` label in Cloud Billing reports to filter costs for this deployment.

## Production Automation

The GitHub Actions workflow in `.github/workflows/deploy-production.yml` deploys on every push to `main`.

It expects GitHub OIDC access to GCP through Workload Identity Federation and a protected GCS bucket for Terraform state.

Required GitHub variables:

```text
GCP_PROJECT_ID
GCP_REGION
GCP_WORKLOAD_IDENTITY_PROVIDER
GCP_DEPLOYER_SERVICE_ACCOUNT
TF_STATE_BUCKET
STOCK_ANALYSIS_AGENT_REDIS_HOST
STOCK_ANALYSIS_AGENT_REDIS_PORT
STOCK_ANALYSIS_AGENT_REDIS_USERNAME
STOCK_ANALYSIS_AGENT_LANGCACHE_ENDPOINT
STOCK_ANALYSIS_AGENT_LANGCACHE_CACHE_ID
STOCK_ANALYSIS_AGENT_AGENT_MEMORY_ENDPOINT
STOCK_ANALYSIS_AGENT_AGENT_MEMORY_STORE_ID
SEC_USER_AGENT
```

Optional GitHub variables:

```text
ARTIFACT_REGISTRY_REPOSITORY_ID
IMAGE_NAME
TF_STATE_PREFIX
CLOUD_RUN_SERVICE_NAME
CLOUD_RUN_INGRESS
CLOUD_RUN_ALLOW_UNAUTHENTICATED
CLOUD_RUN_VPC_CONNECTOR
CLOUD_RUN_VPC_EGRESS
CLOUD_RUN_SMOKE_TEST
```

The workflow bootstraps these Secret Manager secrets. Add enabled secret versions before the full deploy can continue:

```text
stock-analysis-agent-openai-api-key
stock-analysis-agent-redis-password
stock-analysis-agent-langcache-api-key
stock-analysis-agent-agent-memory-api-key
stock-analysis-agent-twelve-data-api-key
stock-analysis-agent-tavily-api-key
```

Terraform creates the secret resources and grants the Cloud Run runtime service account access. Secret payloads are managed outside Terraform so production secrets are not written to Terraform state.

If an older local state already tracks `google_secret_manager_secret_version.app`, remove those resources from state after confirming the secret payloads exist under the Secret Manager names above. Do not let Terraform destroy existing secret versions during migration.

## Manual Apply

Copy the example variables file and fill in real values:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Initialize Terraform with the production state bucket:

```bash
terraform init \
  -backend-config="bucket=your-terraform-state-bucket" \
  -backend-config="prefix=stock-analysis-agent/prod"
```

Create the APIs, Artifact Registry repository, and Secret Manager secrets first:

```bash
terraform apply \
  -target=google_project_service.required \
  -target=google_artifact_registry_repository.app \
  -target=google_secret_manager_secret.app
```

Add the secret versions outside Terraform:

```bash
printf '%s' "$STOCK_ANALYSIS_AGENT_OPENAI_API_KEY" | gcloud secrets versions add stock-analysis-agent-openai-api-key --data-file=-
printf '%s' "$STOCK_ANALYSIS_AGENT_REDIS_PASSWORD" | gcloud secrets versions add stock-analysis-agent-redis-password --data-file=-
printf '%s' "$STOCK_ANALYSIS_AGENT_LANGCACHE_API_KEY" | gcloud secrets versions add stock-analysis-agent-langcache-api-key --data-file=-
printf '%s' "$STOCK_ANALYSIS_AGENT_AGENT_MEMORY_API_KEY" | gcloud secrets versions add stock-analysis-agent-agent-memory-api-key --data-file=-
printf '%s' "$STOCK_ANALYSIS_AGENT_TWELVE_DATA_API_KEY" | gcloud secrets versions add stock-analysis-agent-twelve-data-api-key --data-file=-
printf '%s' "$STOCK_ANALYSIS_AGENT_TAVILY_API_KEY" | gcloud secrets versions add stock-analysis-agent-tavily-api-key --data-file=-
```

Build and push a commit tagged image:

```bash
image="$(terraform output -raw image_url | sed "s/:latest$/:$(git rev-parse --short HEAD)/")"
gcloud builds submit ../../.. --tag "$image"
```

Deploy Cloud Run:

```bash
terraform apply -var="image=$image"
```
