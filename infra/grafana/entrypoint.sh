#!/usr/bin/env sh
set -eu

app_base_url="${STOCK_ANALYSIS_AGENT_APP_BASE_URL:-http://localhost:8080}"
app_base_url="${app_base_url%/}"
escaped_app_base_url=$(printf '%s' "$app_base_url" | sed 's/[&|]/\\&/g')

mkdir -p /var/lib/grafana/dashboards

for dashboard in /opt/stock-analysis-grafana/dashboards/*.json; do
    target="/var/lib/grafana/dashboards/$(basename "$dashboard")"
    sed "s|@@STOCK_ANALYSIS_AGENT_APP_BASE_URL@@|$escaped_app_base_url|g" "$dashboard" > "$target"
done

exec /run.sh
