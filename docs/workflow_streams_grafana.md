# Workflow Streams in Grafana

The agent writes workflow events to Redis streams.

Each chat request creates:

```text
stock-analysis:workflows:{workflowId}
stock-analysis:workflows:{workflowId}:events
stock-analysis:workflows:{workflowId}:checkpoints
stock-analysis:workflows:running
stock-analysis:users:{userId}:workflows
stock-analysis:users:{userId}:conversations
stock-analysis:conversations:{conversationId}:workflows
```

The hash stores workflow metadata, including status, owner id, lease expiry, lease version, attempt, replay origin, and recovery target.
The running sorted set indexes active workflows by lease expiry so another replica can find stale work after a crash.
The event stream stores the ordered agent events, including tool requests, tool results, summaries, actor names, checkpoint markers, and timing.
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
ZRANGEBYSCORE stock-analysis:workflows:running -inf {now}
```

The workflow event sequence panel embeds the Spring app endpoint at `/api/workflows/{workflowId}/timeline`. That endpoint reads the Redis stream and renders each event with elapsed time, actor, event type, step id, duration, summary, and payload details.

Workflow events store payloads for the original request, memory retrieval, coordinator output, specialist agent output, tool calls, and saved turns. This makes the stream useful for reconstructing what the agent saw, not only what step was running.

The workflow metadata panel embeds `/api/workflows/{workflowId}/metadata`. That endpoint reads the Redis hash and renders status, turn, session, previous workflow, timestamps, and duration as compact fields.
It also renders the owner id, lease expiry, lease version, attempt, replay origin, and recovery target.

The checkpoint replay panel embeds `/api/workflows/{workflowId}/replay-context`. That view reads the latest checkpoint, workflow metadata, session workflow list, and events recorded after the checkpoint.

The replay button calls `POST /api/workflows/{workflowId}/replay`. The app creates a new workflow in the same session and conversation, sends the latest checkpoint back through the normal chat execution path, and writes `replayedFromWorkflowId` and `replayCheckpointId` to the new workflow hash. The original workflow is not mutated. Internal replay prompts are not persisted as visible chat turns.

## Crash Recovery

Each running workflow has a short lease. The active application instance renews the lease while the chat request is executing.

On startup and on a timer, each replica scans `stock-analysis:workflows:running` for expired leases. It then runs an atomic Redis script against the workflow hash. The script only claims the workflow when the status is `RUNNING` or `RECOVERING` and the stored lease has expired. Claiming increments `leaseVersion`, increments `attempt`, sets the current replica as `ownerId`, and extends the lease.

The recovery worker reads the latest checkpoint and replays from it into a new workflow. The original workflow is marked `RECOVERED` with `recoveredByWorkflowId`. If no checkpoint exists, the original workflow is marked `FAILED`.

Automatic recovery saves the recovered assistant answer under the original user message. It does not save the internal replay prompt as chat memory.

Workflow data expires after 24 hours.
