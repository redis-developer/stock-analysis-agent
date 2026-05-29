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

## Apply

Copy the example variables file and fill in real values:

```bash
cp terraform.tfvars.example terraform.tfvars
```

Create the APIs and Artifact Registry repository first:

```bash
terraform init
terraform apply -target=google_project_service.required -target=google_artifact_registry_repository.app
```

Build and push the container image:

```bash
gcloud builds submit ../../.. --tag "$(terraform output -raw image_url)"
```

Deploy Cloud Run and create the secrets:

```bash
terraform apply
```

Terraform stores Secret Manager payloads in state. Store the state in a protected backend before using real production secrets.
