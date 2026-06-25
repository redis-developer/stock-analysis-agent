package com.redis.stockanalysisagent.session;

import com.redis.stockanalysisagent.session.dto.ChatSessionWorkflowStep;
import com.redis.stockanalysisagent.workflow.events.WorkflowEventService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class WorkflowStepProjector {

    private final WorkflowEventService workflowEventService;

    @Autowired
    public WorkflowStepProjector(ObjectProvider<WorkflowEventService> workflowEventService) {
        this(workflowEventService.getIfAvailable());
    }

    public WorkflowStepProjector(WorkflowEventService workflowEventService) {
        this.workflowEventService = workflowEventService;
    }

    public List<ChatSessionWorkflowStep> liveSteps(String workflowId, int limit) {
        return workflowEvents(workflowId, limit).stream()
                .map(event -> step(event, false))
                .filter(step -> step != null)
                .toList();
    }

    public List<ChatSessionWorkflowStep> recoveredSteps(String workflowId, String checkpointId, int limit) {
        if (workflowId == null || checkpointId == null) {
            return List.of();
        }

        List<Map<String, String>> events = workflowEvents(workflowId, limit);
        boolean hasCheckpointEvent = events.stream()
                .anyMatch(event -> checkpointId.equals(value(event, "checkpointId")));
        String checkpointedStepId = checkpointedStepId(checkpointId);
        List<ChatSessionWorkflowStep> steps = new ArrayList<>();
        boolean foundCheckpoint = false;
        for (Map<String, String> event : events) {
            ChatSessionWorkflowStep step = step(event, true);
            if (step != null) {
                steps.add(step);
            }
            if (checkpointId.equals(value(event, "checkpointId"))
                    || (!hasCheckpointEvent && isCompletedStep(event, checkpointedStepId))) {
                foundCheckpoint = true;
                break;
            }
        }

        return foundCheckpoint ? List.copyOf(steps) : List.of();
    }

    public String latestCheckpointId(String workflowId, int limit) {
        List<Map<String, String>> events = workflowEvents(workflowId, limit);
        for (int index = events.size() - 1; index >= 0; index--) {
            String checkpointId = blankToNull(events.get(index).get("checkpointId"));
            if (checkpointId != null) {
                return checkpointId;
            }
        }
        return null;
    }

    private List<Map<String, String>> workflowEvents(String workflowId, int limit) {
        if (workflowEventService == null || workflowId == null || workflowId.isBlank()) {
            return List.of();
        }
        return workflowEventService.events(workflowId, limit);
    }

    private String checkpointedStepId(String checkpointId) {
        String value = blankToNull(checkpointId);
        if (value == null) {
            return "";
        }
        int lastSeparator = value.lastIndexOf(':');
        if (lastSeparator < 0) {
            return value;
        }
        int actorSeparator = value.lastIndexOf(':', lastSeparator - 1);
        if (actorSeparator < 0) {
            return value;
        }
        return value.substring(0, actorSeparator);
    }

    private boolean isCompletedStep(Map<String, String> event, String stepId) {
        return stepId != null
                && !stepId.isBlank()
                && stepId.equals(value(event, "stepId"))
                && "completed".equals(value(event, "status"));
    }

    private ChatSessionWorkflowStep step(Map<String, String> event, boolean recovered) {
        if (event == null || event.isEmpty()) {
            return null;
        }

        String stepId = value(event, "stepId");
        if (stepId.isBlank()) {
            return null;
        }

        String toolName = value(event, "toolName");
        return new ChatSessionWorkflowStep(
                stepId,
                label(stepId, toolName),
                value(event, "kind"),
                value(event, "status"),
                longObject(value(event, "durationMs")),
                value(event, "summary"),
                value(event, "actorType"),
                value(event, "actorName"),
                recovered
        );
    }

    private String label(String stepId, String toolName) {
        if (toolName != null && !toolName.isBlank()) {
            return "Tool " + toolName;
        }
        if (stepId == null || stepId.isBlank()) {
            return "Workflow step";
        }
        if (stepId.startsWith("checkpoint:")) {
            return "Checkpoint";
        }
        return stepId.replace(':', ' ').replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    private String value(Map<String, String> fields, String name) {
        String value = fields.get(name);
        return value == null ? "" : value;
    }

    private Long longObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.max(0, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
