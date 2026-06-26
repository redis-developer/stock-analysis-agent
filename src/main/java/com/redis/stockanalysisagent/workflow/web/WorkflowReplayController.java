package com.redis.stockanalysisagent.workflow.web;

import com.redis.stockanalysisagent.chat.ChatService;
import com.redis.stockanalysisagent.session.ConversationId;
import com.redis.stockanalysisagent.workflow.WorkflowMetadata;
import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.WorkflowStatus;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpoint;
import com.redis.stockanalysisagent.workflow.checkpoint.WorkflowCheckpointService;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowReplayController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowReplayController.class);
    private static final int REPLAY_RETRIEVED_MEMORIES_LIMIT = 10;

    private final StringRedisTemplate redisTemplate;
    private final ChatService chatService;
    private final WorkflowCheckpointService checkpointService;
    private final WorkflowService workflowService;

    public WorkflowReplayController(
            StringRedisTemplate redisTemplate,
            ChatService chatService,
            WorkflowCheckpointService checkpointService,
            WorkflowService workflowService
    ) {
        this.redisTemplate = redisTemplate;
        this.chatService = chatService;
        this.checkpointService = checkpointService;
        this.workflowService = workflowService;
    }

    @GetMapping(value = "/{workflowId}/replay-context", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> replayContext(@PathVariable String workflowId) {
        log.info("workflow_replay_context_requested workflowId={}", workflowId);
        Map<Object, Object> metadata = redisTemplate.opsForHash().entries(WorkflowService.workflowKey(workflowId));
        WorkflowCheckpoint checkpoint = checkpointService.latestCheckpoint(workflowId).orElse(null);
        List<ReplayEvent> laterEvents = checkpoint == null ? List.of() : eventsAfter(workflowId, checkpoint.timestamp());
        List<String> sessionWorkflows = sessionWorkflows(metadata);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(render(workflowId, metadata, checkpoint, laterEvents, sessionWorkflows));
    }

    @PostMapping(value = "/{workflowId}/replay", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReplayResponse replay(@PathVariable String workflowId) {
        Map<Object, Object> metadata = redisTemplate.opsForHash().entries(WorkflowService.workflowKey(workflowId));
        if (metadata.isEmpty()) {
            log.warn("workflow_replay_rejected workflowId={} reason=metadata_missing", workflowId);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflow metadata was not found.");
        }

        Optional<String> recoveredByWorkflowId = workflowService.recoveredByWorkflowId(workflowId);
        if (recoveredByWorkflowId.isPresent()) {
            log.info(
                    "workflow_replay_reused workflowId={} replayWorkflowId={}",
                    workflowId,
                    recoveredByWorkflowId.get()
            );
            return new ReplayResponse(
                    workflowId,
                    recoveredByWorkflowId.get(),
                    value(metadata, WorkflowService.REPLAY_CHECKPOINT_ID),
                    WorkflowStatus.RECOVERED.name(),
                    value(metadata, "conversationId")
            );
        }

        WorkflowStatus status = workflowStatus(metadata);
        if (status == WorkflowStatus.WAITING_FOR_APPROVAL) {
            log.warn("workflow_replay_rejected workflowId={} reason=status status={}", workflowId, status);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow status does not allow replay.");
        }

        WorkflowCheckpoint checkpoint = checkpointService.latestCheckpoint(workflowId).orElse(null);
        if (checkpoint == null) {
            log.warn("workflow_replay_rejected workflowId={} reason=checkpoint_missing", workflowId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow has no checkpoint to replay.");
        }

        ReplayTarget target = replayTarget(metadata);
        if (target.userId().isBlank() || target.sessionId().isBlank()) {
            log.warn("workflow_replay_rejected workflowId={} reason=session_metadata_missing", workflowId);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Workflow metadata is missing user or session.");
        }

        log.info(
                "workflow_replay_started workflowId={} checkpointId={} userId={} sessionId={}",
                workflowId,
                checkpoint.checkpointId(),
                target.userId(),
                target.sessionId()
        );
        String clientRequestId = replayClientRequestId(workflowId, checkpoint);
        boolean completedWorkflowReplay = status == WorkflowStatus.COMPLETED;
        WorkflowMetadata claimed = null;
        if (!completedWorkflowReplay) {
            claimed = workflowService.tryClaimForReplay(workflowId)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Workflow is already running, recovered, or not replayable."
                    ));
        }

        try (WorkflowService.Lease ignored = completedWorkflowReplay
                ? WorkflowService.Lease.noop()
                : workflowService.renewLeaseUntilClosed(claimed)) {
            Map<Object, Object> replayMetadata = redisTemplate.opsForHash().entries(WorkflowService.workflowKey(workflowId));
            ChatService.ChatTurn turn;
            try {
                turn = chatService.run(new ChatService.ChatRunRequest(
                        target.userId(),
                        target.sessionId(),
                        checkpointService.replayMessage(workflowId, replayMetadata, checkpoint),
                        clientRequestId,
                        REPLAY_RETRIEVED_MEMORIES_LIMIT,
                        true,
                        false,
                        List.of(),
                        "replay",
                        workflowId,
                        checkpoint.checkpointId(),
                        true,
                        replayVisibleMessage(
                                workflowId,
                                checkpoint.checkpointId(),
                                checkpointService.originalUserMessage(workflowId).orElse("")
                        )
                ));
            } catch (RuntimeException ex) {
                if (!completedWorkflowReplay) {
                    workflowService.fail(claimed, ex);
                }
                throw ex;
            }
            if (!completedWorkflowReplay) {
                workflowService.markRecovered(claimed, turn.workflowId());
            }
            log.info(
                    "workflow_replay_completed workflowId={} replayWorkflowId={} checkpointId={} status={}",
                    workflowId,
                    turn.workflowId(),
                    checkpoint.checkpointId(),
                    turn.workflowStatus()
            );
            return new ReplayResponse(
                    workflowId,
                    turn.workflowId(),
                    checkpoint.checkpointId(),
                    turn.workflowStatus().name(),
                    turn.conversationId()
            );
        }
    }

    private ReplayTarget replayTarget(Map<Object, Object> metadata) {
        String metadataUserId = value(metadata, "userId");
        String metadataSessionId = value(metadata, "sessionId");
        String conversationId = value(metadata, "conversationId");
        if (!conversationId.isBlank()) {
            ConversationId parsed = ConversationId.parse(conversationId);
            String userId = parsed.userId() == null || parsed.userId().isBlank()
                    ? metadataUserId
                    : parsed.userId();
            String sessionId = parsed.sessionId() == null || parsed.sessionId().isBlank()
                    ? metadataSessionId
                    : parsed.sessionId();
            return new ReplayTarget(userId, sessionId);
        }
        return new ReplayTarget(metadataUserId, metadataSessionId);
    }

    private String replayVisibleMessage(String workflowId, String checkpointId, String originalUserMessage) {
        if (originalUserMessage != null && !originalUserMessage.isBlank()) {
            return originalUserMessage.trim();
        }
        return "Replay workflow " + workflowId + " from checkpoint " + checkpointId + ".";
    }

    private List<ReplayEvent> eventsAfter(String workflowId, Instant checkpointTimestamp) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(WorkflowEventService.streamKey(workflowId), Range.unbounded());
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::event)
                .filter(event -> event.timestamp().isAfter(checkpointTimestamp))
                .toList();
    }

    private ReplayEvent event(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        return new ReplayEvent(
                record.getId().getValue(),
                timestamp(values, record.getId().getTimestamp()),
                value(values, "eventType"),
                value(values, "stepId"),
                value(values, "status"),
                value(values, "actorName"),
                value(values, "summary")
        );
    }

    private List<String> sessionWorkflows(Map<Object, Object> metadata) {
        String sessionId = value(metadata, "sessionId");
        if (sessionId.isBlank()) {
            return List.of();
        }
        List<String> workflows = redisTemplate.opsForList().range(WorkflowService.sessionWorkflowsKey(sessionId), 0, -1);
        return workflows == null ? List.of() : workflows;
    }

    private String render(
            String workflowId,
            Map<Object, Object> metadata,
            WorkflowCheckpoint checkpoint,
            List<ReplayEvent> laterEvents,
            List<String> sessionWorkflows
    ) {
        StringBuilder html = new StringBuilder();
        html.append("""
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <style>
                :root { color-scheme: dark; }
                * { box-sizing: border-box; }
                body {
                  margin: 0;
                  background: #111217;
                  color: #d7dce5;
                  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  font-size: 12px;
                }
                .replay { padding: 10px 12px 14px; }
                .header {
                  display: flex;
                  align-items: flex-start;
                  justify-content: space-between;
                  gap: 12px;
                  margin-bottom: 10px;
                }
                .title { color: #f0f3f8; font-weight: 650; font-size: 13px; }
                .subtitle { margin-top: 2px; color: #98a2b3; overflow-wrap: anywhere; }
                .badge {
                  border-radius: 999px;
                  padding: 2px 9px 3px;
                  color: #eef2f8;
                  background: #234f2c;
                  border: 1px solid #3c7847;
                  white-space: nowrap;
                }
                button {
                  border: 1px solid #38639e;
                  border-radius: 6px;
                  padding: 7px 10px;
                  background: #1f3c65;
                  color: #eef2f8;
                  font-weight: 650;
                  cursor: pointer;
                }
                button:disabled {
                  cursor: wait;
                  opacity: 0.65;
                }
                code {
                  color: #f0f3f8;
                  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
                }
                a { color: #9cc8ff; text-decoration: none; }
                a:hover { text-decoration: underline; }
                .grid {
                  display: grid;
                  grid-template-columns: repeat(4, minmax(0, 1fr));
                  gap: 8px;
                }
                .field, .section {
                  min-width: 0;
                  background: #171a21;
                  border: 1px solid #303746;
                  border-radius: 6px;
                }
                .field { padding: 8px 9px; }
                .label { color: #98a2b3; font-size: 11px; margin-bottom: 3px; }
                .value { color: #e7ebf2; font-weight: 600; overflow-wrap: anywhere; }
                .section { margin-top: 8px; padding: 9px; }
                .section-title { color: #f0f3f8; font-weight: 650; margin-bottom: 6px; }
                .event {
                  display: grid;
                  grid-template-columns: 150px 110px minmax(0, 1fr);
                  gap: 8px;
                  padding: 6px 0;
                  border-top: 1px solid #2a2f3a;
                }
                .event:first-of-type { border-top: 0; }
                .muted { color: #98a2b3; overflow-wrap: anywhere; }
                .empty { color: #b4bdca; }
                .action {
                  display: flex;
                  align-items: center;
                  gap: 10px;
                  flex-wrap: wrap;
                }
                .action-result {
                  color: #b4bdca;
                  overflow-wrap: anywhere;
                }
                details { margin-top: 7px; }
                summary { cursor: pointer; color: #b4bdca; }
                pre {
                  margin: 6px 0 0;
                  padding: 8px;
                  max-height: 140px;
                  overflow: auto;
                  color: #d7dce5;
                  background: #0b0d12;
                  border: 1px solid #2a2f3a;
                  border-radius: 5px;
                  white-space: pre-wrap;
                  overflow-wrap: anywhere;
                }
                @media (max-width: 900px) {
                  .grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                  .event { grid-template-columns: 1fr; }
                }
                </style>
                </head>
                <body>
                <div class="replay">
                """);

        html.append("<div class=\"header\"><div><div class=\"title\">Replay context</div><div class=\"subtitle\">")
                .append(escape(workflowId))
                .append("</div></div><span class=\"badge\">replay ready</span></div>");

        if (checkpoint == null) {
            html.append("<div class=\"section\"><div class=\"empty\">No checkpoint found for this workflow yet.</div></div>");
            html.append("</div></body></html>");
            return html.toString();
        }

        html.append("<div class=\"grid\">");
        appendField(html, "Resume step", checkpoint.stepId());
        appendField(html, "Checkpoint", checkpoint.checkpointId());
        appendField(html, "Source event", checkpoint.sourceEventType());
        appendField(html, "Actor", actor(checkpoint));
        appendField(html, "Created", checkpoint.timestamp().toString());
        appendField(html, "Conversation", value(metadata, "conversationId"));
        appendField(html, "Previous turns", previousTurnCount(workflowId, sessionWorkflows));
        appendField(html, "Later events", String.valueOf(laterEvents.size()));
        html.append("</div>");

        html.append("<div class=\"section\"><div class=\"section-title\">Committed output</div><div class=\"value\">")
                .append(escape(checkpoint.summary()))
                .append("</div>");
        appendPayload(html, "input", checkpoint.inputBytes(), checkpoint.inputPayload());
        appendPayload(html, "output", checkpoint.outputBytes(), checkpoint.outputPayload());
        html.append("</div>");

        html.append("<div class=\"section\"><div class=\"section-title\">Replay action</div>")
                .append("<div class=\"action\"><button type=\"button\" id=\"replay-button\">Replay from checkpoint</button>")
                .append("<div id=\"replay-result\" class=\"action-result\"></div></div></div>");

        html.append("<div class=\"section\"><div class=\"section-title\">Events after checkpoint</div>");
        if (laterEvents.isEmpty()) {
            html.append("<div class=\"empty\">No events were recorded after the latest checkpoint.</div>");
        } else {
            for (ReplayEvent event : laterEvents) {
                html.append("<div class=\"event\"><div><div class=\"value\">")
                        .append(escape(event.stepId()))
                        .append("</div><div class=\"muted\">")
                        .append(escape(event.eventType()))
                        .append("</div></div><div class=\"muted\">")
                        .append(escape(event.status()))
                        .append("</div><div><div class=\"muted\">")
                        .append(escape(event.timestamp().toString()))
                        .append("</div><div class=\"value\">")
                        .append(escape(event.summary()))
                        .append("</div></div></div>");
            }
        }
        html.append("</div>");
        appendReplayScript(html, workflowId);
        html.append("</div></body></html>");
        return html.toString();
    }

    private void appendReplayScript(StringBuilder html, String workflowId) {
        html.append("<script>")
                .append("const button=document.getElementById('replay-button');")
                .append("const result=document.getElementById('replay-result');")
                .append("const workflowId='").append(escapeJs(workflowId)).append("';")
                .append("if(button){button.addEventListener('click',async()=>{")
                .append("button.disabled=true;result.textContent='Starting replay...';")
                .append("try{const response=await fetch('/api/workflows/'+encodeURIComponent(workflowId)+'/replay',{method:'POST'});")
                .append("const body=await response.json().catch(()=>({message:'Replay failed.'}));")
                .append("if(!response.ok){throw new Error(body.message||body.error||response.statusText);}")
                .append("const replayWorkflowId=String(body.replayWorkflowId||'');")
                .append("const href=workflowDashboardUrl(replayWorkflowId);")
                .append("result.innerHTML='Created replay workflow <a href=\"'+escapeAttribute(href)+'\" target=\"_top\"><code>'+escapeHtml(replayWorkflowId)+'</code></a>.';")
                .append("}catch(error){result.textContent=error.message||'Replay failed.';button.disabled=false;}")
                .append("});}")
                .append("function workflowDashboardUrl(id){const path='/d/stock-analysis-workflows/workflow-event-log?var-workflow_id='+encodeURIComponent(id);")
                .append("try{if(document.referrer){return new URL(path,new URL(document.referrer).origin).toString();}}catch(error){}")
                .append("return path;}")
                .append("function escapeHtml(value){return String(value||'').replace(/[&<>\"']/g,(char)=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[char]));}")
                .append("function escapeAttribute(value){return escapeHtml(value).replace(/`/g,'&#96;');}")
                .append("</script>");
    }

    private void appendField(StringBuilder html, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        html.append("<div class=\"field\"><div class=\"label\">")
                .append(escape(label))
                .append("</div><div class=\"value\">")
                .append(escape(value))
                .append("</div></div>");
    }

    private void appendPayload(StringBuilder html, String label, String bytes, String payload) {
        if (payload == null || payload.isBlank()) {
            return;
        }
        String size = bytes == null || bytes.isBlank() ? "" : " " + bytes + " B";
        html.append("<details><summary>")
                .append(escape(label))
                .append(escape(size))
                .append("</summary><pre>")
                .append(escape(payload))
                .append("</pre></details>");
    }

    private String actor(WorkflowCheckpoint checkpoint) {
        if (!checkpoint.actorName().isBlank()) {
            return checkpoint.actorName();
        }
        return checkpoint.actorType();
    }

    private String previousTurnCount(String workflowId, List<String> sessionWorkflows) {
        int currentIndex = sessionWorkflows.indexOf(workflowId);
        if (currentIndex >= 0) {
            return String.valueOf(currentIndex);
        }
        return "";
    }

    private String replayClientRequestId(String workflowId, WorkflowCheckpoint checkpoint) {
        return "replay-visible:" + workflowId + ":" + checkpoint.checkpointId() + ":" + UUID.randomUUID();
    }

    private Instant timestamp(Map<Object, Object> values, Long fallbackTimestamp) {
        String timestamp = value(values, "timestamp");
        if (!timestamp.isBlank()) {
            return Instant.parse(timestamp);
        }
        long epochMillis = fallbackTimestamp == null ? 0 : fallbackTimestamp;
        return Instant.ofEpochMilli(epochMillis);
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
    }

    private WorkflowStatus workflowStatus(Map<Object, Object> values) {
        String status = value(values, "status");
        if (status.isBlank()) {
            return WorkflowStatus.RUNNING;
        }
        return WorkflowStatus.valueOf(status);
    }

    private String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String escapeJs(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private record ReplayEvent(
            String streamId,
            Instant timestamp,
            String eventType,
            String stepId,
            String status,
            String actorName,
            String summary
    ) {
    }

    public record ReplayResponse(
            String originalWorkflowId,
            String replayWorkflowId,
            String replayCheckpointId,
            String replayWorkflowStatus,
            String conversationId
    ) {
    }

    private record ReplayTarget(String userId, String sessionId) {
    }
}
