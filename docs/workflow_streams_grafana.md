# Workflow Streams in Grafana

The agent writes workflow events to Redis streams.

Each chat request creates:

```text
stock-analysis:workflows:{workflowId}
stock-analysis:workflows:{workflowId}:lease
stock-analysis:workflows:{workflowId}:events
stock-analysis:workflows:{workflowId}:checkpoints
stock-analysis:workflow-recovery
stock-analysis:users:{userId}:workflows
stock-analysis:users:{userId}:conversations
stock-analysis:conversations:{conversationId}:workflows
```

The hash stores workflow metadata, including status, attempt, replay origin, recovery target, session, conversation, and timestamps.
The lease key is a short TTL key. Its value is the active worker id. Redis expires it when the worker stops renewing it.
The recovery stream stores workflow ids that may need to be checked after a crash or handoff.
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
infra/grafana/dashboards/redis-workflow-states.json
infra/grafana/dashboards/redis-provider-dead-letter.json
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
XRANGE stock-analysis:workflow-recovery - +
XLEN stock-analysis:provider-dead-letter
XREVRANGE stock-analysis:provider-dead-letter + - COUNT 100
```

The workflow event sequence panel embeds the Spring app endpoint at `/api/workflows/{workflowId}/timeline`. That endpoint reads the Redis stream and renders each event with elapsed time, actor, event type, step id, duration, summary, and payload details.

Workflow events store payloads for the original request, memory retrieval, coordinator output, specialist agent output, tool calls, and saved turns. This makes the stream useful for reconstructing what the agent saw, not only what step was running.

The workflow metadata panel embeds `/api/workflows/{workflowId}/metadata`. That endpoint reads the Redis hash and renders status, turn, session, previous workflow, timestamps, and duration as compact fields.
It also renders attempt, replay origin, and recovery target.

The checkpoint replay panel embeds `/api/workflows/{workflowId}/replay-context`. That view reads the latest checkpoint, workflow metadata, session workflow list, and events recorded after the checkpoint.

The replay button calls `POST /api/workflows/{workflowId}/replay`. The app claims the original workflow lease, creates a new workflow in the same session and conversation, sends the latest checkpoint back through the normal chat execution path, and writes `replayedFromWorkflowId` and `replayCheckpointId` to the new workflow hash. When replay finishes, the original workflow is marked `RECOVERED` with `recoveredByWorkflowId`. The chat turn is saved under the original user message, so the internal replay prompt is not shown in the chat UI.

The provider dead letter dashboard reads `stock-analysis:provider-dead-letter`. Each stream entry includes `workflowId`, `stepId`, `providerId`, `cacheName`, `cacheKey`, `attempts`, `reason`, and `failedAt`.

## Crash Recovery

Each running workflow has a short lease key:

```text
stock-analysis:workflows:{workflowId}:lease
```

The active application instance renews that key while the chat request is executing.

On startup and on a timer, each replica reads `stock-analysis:workflow-recovery`. It loads each workflow hash. Terminal workflows are acknowledged. Running workflows are only claimed when `SET stock-analysis:workflows:{workflowId}:lease {workerId} NX PX {ttl}` succeeds. If another replica still owns the lease, the workflow is left for a later scan.

Claiming a workflow changes its hash status to `RECOVERING` and increments `attempt`.

The recovery worker reads the latest checkpoint and replays from it into a new workflow. The original workflow is marked `RECOVERED` with `recoveredByWorkflowId`. If no checkpoint exists, the original workflow is marked `FAILED`.

Automatic recovery saves the recovered assistant answer under the original user message. It does not save the internal replay prompt as chat memory.

## Distributed Worker Safety

Manual replay and automatic recovery use the original workflow hash as the replay record.

```text
stock-analysis:workflows:{workflowId}
status=RECOVERED
recoveredByWorkflowId={newWorkflowId}
```

Before replaying, the app checks `recoveredByWorkflowId`. If a recovered workflow already exists, it returns that workflow id instead of running the agent again.

If no recovered workflow exists, the worker acquires the workflow lease key with `SET NX PX`. Only the lease holder executes the replay. When replay completes, the original workflow hash stores the recovered workflow id.

Workflow data expires after 24 hours.
