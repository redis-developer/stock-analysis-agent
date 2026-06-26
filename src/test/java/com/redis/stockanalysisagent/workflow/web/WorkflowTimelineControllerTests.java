package com.redis.stockanalysisagent.workflow.web;

import com.redis.stockanalysisagent.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowTimelineControllerTests {

    @Test
    void metadataRendersRedisHashWithoutFormatterFailure() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.type(WorkflowService.workflowKey("workflow-1"))).thenReturn(DataType.HASH);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-1"))).thenReturn(Map.of(
                "workflowId", "workflow-1",
                "status", "COMPLETED",
                "userId", "raphael",
                "createdAt", "2026-06-25T13:00:00Z",
                "updatedAt", "2026-06-25T13:00:01Z",
                "finishedAt", "2026-06-25T13:00:01Z"
        ));
        WorkflowTimelineController controller = new WorkflowTimelineController(redisTemplate);

        String body = controller.metadata("workflow-1").getBody();

        assertThat(body)
                .contains("Redis hash")
                .contains("workflow-1")
                .contains("COMPLETED")
                .contains("100%");
    }

    @Test
    void approvalsRenderOnlyWaitingWorkflows() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        when(redisTemplate.keys("stock-analysis:workflows:*")).thenReturn(Set.of(
                WorkflowService.workflowKey("workflow-waiting"),
                WorkflowService.workflowKey("workflow-completed")
        ));
        when(redisTemplate.type(WorkflowService.workflowKey("workflow-waiting"))).thenReturn(DataType.HASH);
        when(redisTemplate.type(WorkflowService.workflowKey("workflow-completed"))).thenReturn(DataType.HASH);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-waiting"))).thenReturn(Map.of(
                "workflowId", "workflow-waiting",
                "status", "WAITING_FOR_APPROVAL",
                "userId", "raphael",
                "conversationId", "raphael:session-1",
                "approvalToolName", "runMarketDataAgent",
                "approvalTicker", "DUOL",
                "approvalStatus", "PENDING",
                "approvalAgentType", "MARKET_DATA",
                "updatedAt", "2026-06-25T13:00:01Z"
        ));
        when(hashOperations.entries(WorkflowService.workflowKey("workflow-completed"))).thenReturn(Map.of(
                "workflowId", "workflow-completed",
                "status", "COMPLETED",
                "userId", "raphael",
                "updatedAt", "2026-06-25T13:00:02Z"
        ));
        WorkflowTimelineController controller = new WorkflowTimelineController(redisTemplate);

        String body = controller.approvals(null, null).getBody();

        assertThat(body)
                .contains("Pending human approvals")
                .contains("workflow-waiting")
                .contains("runMarketDataAgent")
                .contains("DUOL")
                .doesNotContain("workflow-completed");
    }
}
