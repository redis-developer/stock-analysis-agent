package com.redis.stockanalysisagent.workflow.web;

import com.redis.stockanalysisagent.workflow.WorkflowService;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowTimelineController {

    private final StringRedisTemplate redisTemplate;

    public WorkflowTimelineController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping(value = "/states", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> states(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String conversationId
    ) {
        List<WorkflowState> states;
        try {
            states = workflowStates(userId, conversationId);
        } catch (DataAccessException ex) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(renderError("Workflow states unavailable", ex.getMostSpecificCause().getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(renderStates(states, clean(userId), clean(conversationId)));
    }

    @GetMapping(value = "/approvals", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> approvals(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String conversationId
    ) {
        List<WorkflowState> approvals;
        try {
            approvals = workflowStates(userId, conversationId).stream()
                    .filter(state -> "WAITING_FOR_APPROVAL".equals(value(state.fields(), "status")))
                    .toList();
        } catch (DataAccessException ex) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(renderError("Workflow approvals unavailable", ex.getMostSpecificCause().getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(renderApprovals(approvals, clean(userId), clean(conversationId)));
    }

    @GetMapping(value = "/{workflowId}/timeline", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> timeline(@PathVariable String workflowId) {
        List<TimelineEvent> events;
        try {
            events = events(clean(workflowId));
        } catch (DataAccessException ex) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(renderTimelineError(workflowId, "Workflow event stream unavailable", ex.getMostSpecificCause().getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(render(clean(workflowId), events));
    }

    @GetMapping(value = "/{workflowId}/metadata", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> metadata(@PathVariable String workflowId) {
        String id = clean(workflowId);
        Map<Object, Object> metadata;
        try {
            metadata = workflowMetadata(id);
        } catch (DataAccessException ex) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(renderError("Workflow metadata unavailable", ex.getMostSpecificCause().getMessage()));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(renderMetadata(id, metadata));
    }

    private List<TimelineEvent> events(String workflowId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(WorkflowEventService.streamKey(workflowId), Range.unbounded());
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        return records.stream()
                .map(this::event)
                .toList();
    }

    private Map<Object, Object> workflowMetadata(String workflowId) {
        if (workflowId.isBlank()) {
            return Map.of();
        }

        String key = WorkflowService.workflowKey(workflowId);
        DataType type = redisTemplate.type(key);
        if (type == null || type == DataType.NONE) {
            return Map.of();
        }
        if (type != DataType.HASH) {
            return Map.of(
                    "workflowId", workflowId,
                    "status", "UNAVAILABLE",
                    "failureReason", "Expected Redis hash at " + key + " but found " + type.code() + "."
            );
        }
        Map<Object, Object> metadata = redisTemplate.opsForHash().entries(key);
        return metadata == null ? Map.of() : metadata;
    }

    private TimelineEvent event(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        return new TimelineEvent(
                record.getId().getValue(),
                timestamp(values, record.getId().getTimestamp()),
                value(values, "eventType"),
                value(values, "stepId"),
                value(values, "status"),
                value(values, "kind"),
                value(values, "actorType"),
                value(values, "actorName"),
                value(values, "durationMs"),
                value(values, "summary"),
                value(values, "toolName"),
                value(values, "inputBytes"),
                value(values, "inputPayload"),
                value(values, "outputBytes"),
                value(values, "outputPayload"),
                value(values, "errorType")
        );
    }

    private String render(String workflowId, List<TimelineEvent> events) {
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
                .header {
                  position: sticky;
                  top: 0;
                  z-index: 2;
                  display: flex;
                  align-items: center;
                  gap: 10px;
                  padding: 10px 12px;
                  background: #111217;
                  border-bottom: 1px solid #2a2f3a;
                }
                .title { font-weight: 650; color: #f0f3f8; }
                .metric {
                  color: #b4bdca;
                  border: 1px solid #303746;
                  border-radius: 999px;
                  padding: 2px 8px;
                  white-space: nowrap;
                }
                .empty { padding: 18px 14px; color: #b4bdca; }
                .sequence { padding: 10px 12px 18px; }
                .event {
                  display: grid;
                  grid-template-columns: 76px 18px minmax(0, 1fr);
                  column-gap: 10px;
                  position: relative;
                }
                .event + .event { margin-top: 8px; }
                .elapsed {
                  padding-top: 8px;
                  color: #98a2b3;
                  font-variant-numeric: tabular-nums;
                  text-align: right;
                }
                .rail { position: relative; }
                .rail::before {
                  content: "";
                  position: absolute;
                  top: -8px;
                  bottom: -16px;
                  left: 8px;
                  width: 2px;
                  background: #2f3745;
                }
                .event:first-child .rail::before { top: 9px; }
                .event:last-child .rail::before { bottom: calc(100% - 10px); }
                .dot {
                  position: absolute;
                  top: 10px;
                  left: 4px;
                  width: 10px;
                  height: 10px;
                  border-radius: 50%;
                  background: var(--status-color);
                  box-shadow: 0 0 0 3px rgba(255, 255, 255, 0.06);
                }
                .card {
                  min-width: 0;
                  padding: 7px 10px 8px;
                  background: #171a21;
                  border: 1px solid #303746;
                  border-left: 4px solid var(--status-color);
                  border-radius: 6px;
                }
                .topline {
                  display: flex;
                  flex-wrap: wrap;
                  align-items: center;
                  gap: 6px;
                  min-width: 0;
                }
                .step {
                  color: #f0f3f8;
                  font-weight: 650;
                  overflow-wrap: anywhere;
                }
                .badge {
                  border-radius: 999px;
                  padding: 1px 7px 2px;
                  background: color-mix(in srgb, var(--status-color) 22%, transparent);
                  color: #eef2f8;
                  border: 1px solid color-mix(in srgb, var(--status-color) 60%, #303746);
                  font-size: 11px;
                  line-height: 16px;
                }
                .muted { color: #98a2b3; }
                .summary {
                  margin-top: 4px;
                  color: #c7ceda;
                  line-height: 1.35;
                  overflow-wrap: anywhere;
                }
                details { margin-top: 6px; }
                summary { cursor: pointer; color: #b4bdca; }
                pre {
                  margin: 6px 0 0;
                  padding: 8px;
                  max-height: 170px;
                  overflow: auto;
                  color: #d7dce5;
                  background: #0b0d12;
                  border: 1px solid #2a2f3a;
                  border-radius: 5px;
                  white-space: pre-wrap;
                  overflow-wrap: anywhere;
                }
                </style>
                </head>
                <body>
                """);

        if (events.isEmpty()) {
            html.append("<div class=\"empty\">No Redis stream events found for workflow ")
                    .append(escape(workflowId))
                    .append(".</div></body></html>");
            return html.toString();
        }

        Instant startedAt = events.getFirst().timestamp();
        Instant finishedAt = events.getLast().timestamp();
        long durationMs = Math.max(0, Duration.between(startedAt, finishedAt).toMillis());
        long failedCount = events.stream().filter(event -> "failed".equals(event.status())).count();
        long toolCount = events.stream().filter(event -> event.eventType().startsWith("tool.")).count();

        html.append("<div class=\"header\"><div class=\"title\">Workflow event sequence</div>")
                .append(metric(events.size() + " events"))
                .append(metric("duration " + formatDuration(durationMs)))
                .append(metric(toolCount + " tool events"))
                .append(metric(failedCount + " failed"))
                .append("</div><div class=\"sequence\">");

        for (TimelineEvent event : events) {
            long elapsedMs = Math.max(0, Duration.between(startedAt, event.timestamp()).toMillis());
            appendEvent(html, event, elapsedMs);
        }

        html.append("</div></body></html>");
        return html.toString();
    }

    private String renderTimelineError(String workflowId, String title, String message) {
        return document("""
                <div class="metadata-empty">
                  <strong>%s</strong>
                  <div>Workflow %s</div>
                  <div>%s</div>
                </div>
                """.formatted(escape(title), escape(clean(workflowId)), escape(message)));
    }

    private List<WorkflowState> workflowStates(String userId, String conversationId) {
        List<String> ids = workflowIds(userId, conversationId);
        if (ids.isEmpty()) {
            return List.of();
        }

        List<WorkflowState> states = new ArrayList<>();
        for (String workflowId : new LinkedHashSet<>(ids)) {
            if (workflowId == null || workflowId.isBlank()) {
                continue;
            }
            Map<Object, Object> fields = redisTemplate.opsForHash().entries(WorkflowService.workflowKey(workflowId));
            if (!fields.isEmpty()) {
                states.add(new WorkflowState(workflowId, fields));
            }
        }
        states.sort(Comparator.comparing(this::stateUpdatedAt).reversed());
        return states;
    }

    private List<String> workflowIds(String userId, String conversationId) {
        String conversation = clean(conversationId);
        if (!conversation.isBlank()) {
            return list(WorkflowService.conversationWorkflowsKey(conversation));
        }

        String user = clean(userId);
        if (!user.isBlank()) {
            return list(WorkflowService.userWorkflowsKey(user));
        }

        String prefix = WorkflowService.workflowKey("");
        Set<String> keys = redisTemplate.keys(prefix + "*");
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        return keys.stream()
                .filter(key -> redisTemplate.type(key) == DataType.HASH)
                .map(key -> key.substring(prefix.length()))
                .sorted()
                .toList();
    }

    private List<String> list(String key) {
        List<String> values = redisTemplate.opsForList().range(key, 0, -1);
        return values == null ? List.of() : values;
    }

    private Instant stateUpdatedAt(WorkflowState state) {
        String updatedAt = value(state.fields(), "updatedAt");
        if (updatedAt.isBlank()) {
            updatedAt = value(state.fields(), "createdAt");
        }
        try {
            return updatedAt.isBlank() ? Instant.EPOCH : Instant.parse(updatedAt);
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }

    private String renderStates(List<WorkflowState> states, String userId, String conversationId) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"states\"><div class=\"states-header\"><div><div class=\"states-title\">Workflow states</div><div class=\"states-subtitle\">");
        if (!conversationId.isBlank()) {
            html.append("Conversation ").append(escape(conversationId));
        } else if (!userId.isBlank()) {
            html.append("User ").append(escape(userId));
        } else {
            html.append("All workflow hashes");
        }
        html.append("</div></div><div class=\"states-count\">")
                .append(states.size())
                .append("</div></div>");

        if (states.isEmpty()) {
            html.append("<div class=\"metadata-empty\">No workflow hashes found.</div></div>");
            return document(html.toString());
        }

        html.append("""
                <table class="states-table">
                <thead>
                <tr>
                  <th>workflowId</th>
                  <th>status</th>
                  <th>userId</th>
                  <th>sessionId</th>
                  <th>conversationId</th>
                  <th>turn</th>
                  <th>attempt</th>
                  <th>updatedAt</th>
                  <th>recoveredByWorkflowId</th>
                </tr>
                </thead>
                <tbody>
                """);
        for (WorkflowState state : states) {
            Map<Object, Object> fields = state.fields();
            String status = value(fields, "status");
            html.append("<tr><td class=\"mono\">")
                    .append(escape(state.workflowId()))
                    .append("</td><td><span class=\"metadata-status ")
                    .append(escape(status.toLowerCase()))
                    .append("\">")
                    .append(escape(status))
                    .append("</span></td><td>")
                    .append(escape(value(fields, "userId")))
                    .append("</td><td class=\"mono\">")
                    .append(escape(value(fields, "sessionId")))
                    .append("</td><td class=\"mono\">")
                    .append(escape(value(fields, "conversationId")))
                    .append("</td><td>")
                    .append(escape(value(fields, "turnIndex")))
                    .append("</td><td>")
                    .append(escape(value(fields, "attempt")))
                    .append("</td><td class=\"mono\">")
                    .append(escape(value(fields, "updatedAt")))
                    .append("</td><td class=\"mono\">")
                    .append(escape(value(fields, WorkflowService.RECOVERED_BY_WORKFLOW_ID)))
                    .append("</td></tr>");
        }
        html.append("</tbody></table></div>");
        return document(html.toString());
    }

    private String renderApprovals(List<WorkflowState> states, String userId, String conversationId) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"states\"><div class=\"states-header\"><div><div class=\"states-title\">Pending human approvals</div><div class=\"states-subtitle\">");
        if (!conversationId.isBlank()) {
            html.append("Conversation ").append(escape(conversationId));
        } else if (!userId.isBlank()) {
            html.append("User ").append(escape(userId));
        } else {
            html.append("All workflow hashes");
        }
        html.append("</div></div><div class=\"states-count\">")
                .append(states.size())
                .append("</div></div>");

        if (states.isEmpty()) {
            html.append("<div class=\"metadata-empty\">No workflows are waiting for human approval.</div></div>");
            return document(html.toString());
        }

        html.append("""
                <table class="states-table">
                <thead>
                <tr>
                  <th>workflowId</th>
                  <th>tool</th>
                  <th>ticker</th>
                  <th>approval</th>
                  <th>agent</th>
                  <th>userId</th>
                  <th>conversationId</th>
                  <th>updatedAt</th>
                </tr>
                </thead>
                <tbody>
                """);
        for (WorkflowState state : states) {
            Map<Object, Object> fields = state.fields();
            String workflowId = state.workflowId();
            html.append("<tr><td class=\"mono\"><button class=\"workflow-link\" type=\"button\" data-workflow-id=\"")
                    .append(escape(workflowId))
                    .append("\" onclick=\"openWorkflow(this.dataset.workflowId)\">")
                    .append(escape(workflowId))
                    .append("</button></td><td>")
                    .append(escape(value(fields, "approvalToolName")))
                    .append("</td><td>")
                    .append(escape(value(fields, "approvalTicker")))
                    .append("</td><td><span class=\"metadata-status waiting_for_approval\">")
                    .append(escape(value(fields, "approvalStatus")))
                    .append("</span></td><td>")
                    .append(escape(value(fields, "approvalAgentType")))
                    .append("</td><td>")
                    .append(escape(value(fields, "userId")))
                    .append("</td><td class=\"mono\">")
                    .append(escape(value(fields, "conversationId")))
                    .append("</td><td class=\"mono\">")
                    .append(escape(value(fields, "updatedAt")))
                    .append("</td></tr>");
        }
        html.append("</tbody></table></div>");
        return document(html.toString());
    }

    private String renderMetadata(String workflowId, Map<Object, Object> fields) {
        if (fields.isEmpty()) {
            return document("""
                    <div class="metadata-empty">No Redis hash found for workflow %s.</div>
                    """.formatted(escape(workflowId)));
        }

        String status = value(fields, "status");
        String createdAt = value(fields, "createdAt");
        String updatedAt = value(fields, "updatedAt");
        String finishedAt = value(fields, "finishedAt");
        String duration = workflowDuration(createdAt, finishedAt.isBlank() ? updatedAt : finishedAt);

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"metadata\"><div class=\"metadata-header\"><div><div class=\"metadata-title\">Redis hash</div><div class=\"metadata-id\">")
                .append(escape(workflowId))
                .append("</div></div><span class=\"metadata-status ")
                .append(escape(status.toLowerCase()))
                .append("\">")
                .append(escape(status))
                .append("</span></div><div class=\"metadata-grid\">");

        appendMetadataField(html, "Turn", value(fields, "turnIndex"));
        appendMetadataField(html, "Duration", duration);
        appendMetadataField(html, "User", value(fields, "userId"));
        appendMetadataField(html, "Session", value(fields, "sessionId"));
        appendMetadataField(html, "Conversation", value(fields, "conversationId"));
        appendMetadataField(html, "Client request", value(fields, "clientRequestId"));
        appendMetadataField(html, "Previous workflow", value(fields, "previousWorkflowId"));
        appendMetadataField(html, "Replayed from", value(fields, WorkflowService.REPLAYED_FROM_WORKFLOW_ID));
        appendMetadataField(html, "Replay checkpoint", value(fields, WorkflowService.REPLAY_CHECKPOINT_ID));
        appendMetadataField(html, "Recovered by", value(fields, WorkflowService.RECOVERED_BY_WORKFLOW_ID));
        appendMetadataField(html, "Attempt", value(fields, "attempt"));
        appendMetadataField(html, "Created", createdAt);
        appendMetadataField(html, "Updated", updatedAt);
        appendMetadataField(html, "Finished", finishedAt);
        appendMetadataField(html, "Failure", value(fields, "failureReason"));

        html.append("</div></div>");
        return document(html.toString());
    }

    private void appendMetadataField(StringBuilder html, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        html.append("<div class=\"metadata-field\"><div class=\"metadata-label\">")
                .append(escape(label))
                .append("</div><div class=\"metadata-value\">")
                .append(escape(value))
                .append("</div></div>");
    }

    private String workflowDuration(String startedAt, String endedAt) {
        if (startedAt.isBlank() || endedAt.isBlank()) {
            return "";
        }
        try {
            return formatDuration(Duration.between(Instant.parse(startedAt), Instant.parse(endedAt)).toMillis());
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private String document(String body) {
        return """
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
                .metadata {
                  padding: 10px 12px;
                }
                .metadata-header {
                  display: flex;
                  align-items: flex-start;
                  justify-content: space-between;
                  gap: 12px;
                  margin-bottom: 10px;
                }
                .metadata-title {
                  color: #f0f3f8;
                  font-weight: 650;
                  font-size: 13px;
                }
                .metadata-id {
                  margin-top: 2px;
                  color: #98a2b3;
                  overflow-wrap: anywhere;
                }
                .metadata-status {
                  border-radius: 999px;
                  padding: 2px 9px 3px;
                  color: #eef2f8;
                  border: 1px solid #303746;
                  white-space: nowrap;
                }
                .metadata-status.completed { background: #234f2c; border-color: #3c7847; }
                .metadata-status.failed { background: #5f1f2a; border-color: #9f3445; }
                .metadata-status.running { background: #1f3c65; border-color: #38639e; }
                .metadata-status.recovering { background: #4d3f1f; border-color: #8b7133; }
                .metadata-status.recovered { background: #1f5753; border-color: #378c85; }
                .metadata-status.waiting_for_approval { background: #52395d; border-color: #875c9a; }
                .states { padding: 10px 12px 14px; }
                .states-header {
                  display: flex;
                  align-items: flex-start;
                  justify-content: space-between;
                  gap: 12px;
                  margin-bottom: 10px;
                }
                .states-title {
                  color: #f0f3f8;
                  font-weight: 650;
                  font-size: 13px;
                }
                .states-subtitle {
                  margin-top: 2px;
                  color: #98a2b3;
                  overflow-wrap: anywhere;
                }
                .states-count {
                  min-width: 34px;
                  text-align: center;
                  border-radius: 999px;
                  padding: 2px 9px 3px;
                  color: #b9f7f0;
                  border: 1px solid #347e80;
                  background: #173b41;
                  font-weight: 700;
                }
                .states-table {
                  width: 100%;
                  border-collapse: collapse;
                }
                .states-table th,
                .states-table td {
                  padding: 7px 8px;
                  border-bottom: 1px solid #2a2f3a;
                  text-align: left;
                  vertical-align: top;
                }
                .states-table th {
                  position: sticky;
                  top: 0;
                  z-index: 1;
                  color: #98a2b3;
                  background: #111217;
                  font-weight: 650;
                }
                .states-table td {
                  color: #d7dce5;
                  overflow-wrap: anywhere;
                }
                .workflow-link {
                  appearance: none;
                  border: 0;
                  padding: 0;
                  background: transparent;
                  color: #9cc9ff;
                  cursor: pointer;
                  font: inherit;
                  text-align: left;
                  text-decoration: underline;
                  overflow-wrap: anywhere;
                }
                .mono {
                  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
                  font-size: 11px;
                }
                .metadata-grid {
                  display: grid;
                  grid-template-columns: repeat(4, minmax(0, 1fr));
                  gap: 8px;
                }
                .metadata-field {
                  min-width: 0;
                  padding: 8px 9px;
                  background: #171a21;
                  border: 1px solid #303746;
                  border-radius: 6px;
                }
                .metadata-label {
                  color: #98a2b3;
                  font-size: 11px;
                  margin-bottom: 3px;
                }
                .metadata-value {
                  color: #e7ebf2;
                  font-weight: 600;
                  overflow-wrap: anywhere;
                }
                .metadata-empty {
                  padding: 16px 14px;
                  color: #b4bdca;
                }
                @media (max-width: 900px) {
                  .metadata-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
                }
                </style>
                <script>
                function openWorkflow(workflowId) {
                  window.top.location.href = "/d/stock-analysis-workflows/workflow-event-log?var-workflow_id=" + encodeURIComponent(workflowId);
                }
                </script>
                </head>
                <body>
                __BODY__
                </body>
                </html>
                """.replace("__BODY__", body);
    }

    private String renderError(String title, String message) {
        return document("""
                <div class="metadata-empty">
                  <strong>%s</strong>
                  <div>%s</div>
                </div>
                """.formatted(escape(title), escape(message)));
    }

    private String metric(String value) {
        return "<div class=\"metric\">" + escape(value) + "</div>";
    }

    private void appendEvent(StringBuilder html, TimelineEvent event, long elapsedMs) {
        String color = statusColor(event.status());
        html.append("<div class=\"event\" style=\"--status-color: ")
                .append(color)
                .append("\"><div class=\"elapsed\">")
                .append(formatElapsed(elapsedMs))
                .append("</div><div class=\"rail\"><div class=\"dot\"></div></div><div class=\"card\"><div class=\"topline\"><span class=\"step\">")
                .append(escape(event.stepId()))
                .append("</span><span class=\"badge\">")
                .append(escape(event.status()))
                .append("</span>")
                .append(meta(event.eventType()))
                .append(meta(event.actorName()))
                .append(meta(durationLabel(event.durationMs())))
                .append(meta(errorLabel(event.errorType())))
                .append("</div><div class=\"summary\">")
                .append(escape(event.summary()))
                .append("</div>");
        appendPayload(html, "input", event.inputBytes(), event.inputPayload());
        appendPayload(html, "output", event.outputBytes(), event.outputPayload());
        html.append("</div></div>");
    }

    private String meta(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return "<span class=\"muted\">" + escape(value) + "</span>";
    }

    private String durationLabel(String durationMs) {
        if (durationMs == null || durationMs.isBlank()) {
            return "";
        }
        try {
            return formatDuration(Long.parseLong(durationMs));
        } catch (NumberFormatException ex) {
            return durationMs + "ms";
        }
    }

    private String errorLabel(String errorType) {
        return errorType == null || errorType.isBlank() ? "" : "error " + errorType;
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

    private String statusColor(String status) {
        return switch (status) {
            case "completed" -> "#5a9555";
            case "failed" -> "#b43b4d";
            case "running" -> "#4f79bd";
            default -> "#8a728d";
        };
    }

    private String formatElapsed(long elapsedMs) {
        return "+" + formatDuration(elapsedMs);
    }

    private String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        long seconds = durationMs / 1000;
        long millis = durationMs % 1000;
        return seconds + "." + String.format("%03d", millis) + "s";
    }

    private Instant timestamp(Map<Object, Object> values, Long fallbackTimestamp) {
        String timestamp = value(values, "timestamp");
        if (!timestamp.isBlank()) {
            try {
                return Instant.parse(timestamp);
            } catch (RuntimeException ignored) {
                // Older demo events may not have stored ISO timestamps.
            }
        }
        long epochMillis = fallbackTimestamp == null ? 0 : fallbackTimestamp;
        return Instant.ofEpochMilli(epochMillis);
    }

    private String value(Map<Object, Object> values, String key) {
        Object value = values.get(key);
        return value == null ? "" : value.toString();
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

    private String clean(String value) {
        if (value == null || value.isBlank() || value.contains("empty array") || value.contains("${")) {
            return "";
        }
        return value.trim();
    }

    private record TimelineEvent(
            String streamId,
            Instant timestamp,
            String eventType,
            String stepId,
            String status,
            String kind,
            String actorType,
            String actorName,
            String durationMs,
            String summary,
            String toolName,
            String inputBytes,
            String inputPayload,
            String outputBytes,
            String outputPayload,
            String errorType
    ) {
    }

    private record WorkflowState(
            String workflowId,
            Map<Object, Object> fields
    ) {
    }
}
