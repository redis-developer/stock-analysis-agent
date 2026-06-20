# Workflow Streams in Grafana

The agent writes workflow events to Redis streams.

Each chat request creates:

```text
stock-analysis:workflows:{workflowId}
stock-analysis:workflows:{workflowId}:events
stock-analysis:workflows:{workflowId}:checkpoints
stock-analysis:users:{userId}:workflows
stock-analysis:users:{userId}:conversations
stock-analysis:conversations:{conversationId}:workflows
```

The hash stores workflow metadata. The event stream stores the ordered agent events, including tool requests, tool results, summaries, actor names, checkpoint markers, and timing.
The checkpoint stream stores completed replayable steps, starting with completed agent steps and completed tool calls.
The user and conversation lists let Grafana filter workflow ids by user, then conversation, before reading the selected workflow hash and stream.

## Run

Start the app and complete one chat request first.

Start Grafana:

```bash
docker compose -f docker-compose.grafana.yml up
```

Open Grafana:

```text
http://localhost:3000/d/stock-analysis-workflows/workflow-event-log
```

The local user is `admin` and the local password is `admin`. Anonymous admin access is enabled for the local demo.

## Cloud Run

The Cloud Run image lives in `infra/grafana`. It installs the Redis datasource plugin at build time and renders the dashboard JSON at startup. The startup script replaces `@@STOCK_ANALYSIS_AGENT_APP_BASE_URL@@` with `STOCK_ANALYSIS_AGENT_APP_BASE_URL`.

Required runtime settings:

1. `STOCK_ANALYSIS_AGENT_APP_BASE_URL`
2. `STOCK_ANALYSIS_AGENT_REDIS_HOST`
3. `STOCK_ANALYSIS_AGENT_REDIS_PORT`
4. `STOCK_ANALYSIS_AGENT_REDIS_USERNAME`
5. `STOCK_ANALYSIS_AGENT_REDIS_PASSWORD`

Use Secret Manager for `STOCK_ANALYSIS_AGENT_REDIS_PASSWORD`.

## Redis Connection

The compose file points Grafana at Redis on the host machine:

```text
host.docker.internal:6379
```

For a remote Redis instance, export the same variables used by the app before starting Grafana:

```bash
export STOCK_ANALYSIS_AGENT_REDIS_HOST=your_redis_host
export STOCK_ANALYSIS_AGENT_REDIS_PORT=6379
export STOCK_ANALYSIS_AGENT_REDIS_PASSWORD=your_redis_password
docker compose -f docker-compose.grafana.yml up
```

## Dashboard

The dashboard is provisioned from:

```text
infra/grafana/dashboards/redis-workflow-streams.json
```

It uses the Redis datasource plugin and these commands:

```text
KEYS stock-analysis:workflows:*:events
KEYS stock-analysis:users:*:workflows
LRANGE stock-analysis:users:{userId}:conversations 0 -1
LRANGE stock-analysis:conversations:{conversationId}:workflows 0 -1
XLEN stock-analysis:workflows:{workflowId}:events
HGETALL stock-analysis:workflows:{workflowId}
XRANGE stock-analysis:workflows:{workflowId}:events - +
XREVRANGE stock-analysis:workflows:{workflowId}:checkpoints + - COUNT 1
```

The workflow event sequence panel embeds the Spring app endpoint at `/api/workflows/{workflowId}/timeline`. That endpoint reads the Redis stream and renders each event with elapsed time, actor, event type, step id, duration, summary, and tool payload details.

The workflow metadata panel embeds `/api/workflows/{workflowId}/metadata`. That endpoint reads the Redis hash and renders status, turn, session, previous workflow, timestamps, and duration as compact fields.

Workflow data expires after 24 hours.
