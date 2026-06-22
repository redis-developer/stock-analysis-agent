package com.redis.stockanalysisagent.workflow;

import com.redis.stockanalysisagent.agent.AgentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class WorkflowApprovalService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowApprovalService.class);
    private static final String KEY_PREFIX = "stock-analysis:workflow-approvals:";
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final List<String> APPROVABLE_TOOLS = List.of(
            "runMarketDataAgent",
            "runFundamentalsAgent",
            "runNewsAgent",
            "runTechnicalAnalysisAgent",
            "runBacktestAgent"
    );

    private final StringRedisTemplate redisTemplate;
    private final WorkflowService workflowService;
    private final Clock clock;
    private final ThreadLocal<Set<String>> approvalRequiredTools = new ThreadLocal<>();

    @Autowired
    public WorkflowApprovalService(StringRedisTemplate redisTemplate, WorkflowService workflowService) {
        this(redisTemplate, workflowService, Clock.systemUTC());
    }

    WorkflowApprovalService(StringRedisTemplate redisTemplate, WorkflowService workflowService, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.workflowService = workflowService;
        this.clock = clock;
    }

    public void setRequireApproval(boolean enabled) {
        setApprovalRequiredTools(enabled ? APPROVABLE_TOOLS : List.of());
    }

    public void setApprovalRequiredTools(Iterable<String> toolNames) {
        Set<String> normalized = new HashSet<>(normalizeToolNames(toolNames));
        if (normalized.isEmpty()) {
            approvalRequiredTools.remove();
            return;
        }
        approvalRequiredTools.set(normalized);
    }

    public void clearRequireApproval() {
        approvalRequiredTools.remove();
    }

    public ToolApproval requireApproval(String toolName, AgentType agentType, String ticker, String question) {
        if (!approvalRequiredTools().contains(toolName)) {
            return null;
        }

        String activeWorkflowId = WorkflowContextHolder.workflowId().orElse("");
        if (activeWorkflowId.isBlank()) {
            return null;
        }

        String approvalWorkflowId = WorkflowContextHolder.approvalWorkflowId().orElse(activeWorkflowId);
        String arguments = arguments(ticker, question);
        String approvalId = approvalId(approvalWorkflowId, toolName, arguments);
        ToolApproval existing = read(approvalId);
        if (existing != null) {
            if (existing.approved() || existing.rejected()) {
                return existing;
            }
            recordPendingApproval(activeWorkflowId, existing);
            throw new ApprovalRequiredException(existing);
        }

        ToolApproval approval = createPendingApproval(
                approvalId,
                approvalWorkflowId,
                activeWorkflowId,
                toolName,
                agentType,
                ticker,
                question,
                arguments
        );
        log.info(
                "workflow_approval_required approvalId={} workflowId={} activeWorkflowId={} toolName={} agentType={} ticker={}",
                approval.approvalId(),
                approval.workflowId(),
                approval.activeWorkflowId(),
                approval.toolName(),
                approval.agentType(),
                approval.ticker()
        );
        throw new ApprovalRequiredException(approval);
    }

    public static List<String> approvableTools() {
        return APPROVABLE_TOOLS;
    }

    public static List<String> normalizeToolNames(Iterable<String> toolNames) {
        if (toolNames == null) {
            return List.of();
        }
        List<String> tools = APPROVABLE_TOOLS.stream()
                .filter(toolName -> containsTool(toolNames, toolName))
                .toList();
        return List.copyOf(tools);
    }

    public ToolApproval approve(String approvalId) {
        return decide(approvalId, STATUS_APPROVED);
    }

    public ToolApproval reject(String approvalId) {
        return decide(approvalId, STATUS_REJECTED);
    }

    public ToolApproval readRequired(String approvalId) {
        ToolApproval approval = read(approvalId);
        if (approval == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval was not found.");
        }
        return approval;
    }

    public Optional<ToolApproval> readApproval(String approvalId) {
        return Optional.ofNullable(read(approvalId));
    }

    public Optional<ToolApproval> pendingApprovalForWorkflow(String workflowId) {
        if (workflowId == null || workflowId.isBlank()) {
            return Optional.empty();
        }

        Object approvalId = redisTemplate.opsForHash().get(WorkflowService.workflowKey(workflowId), "pendingApprovalId");
        ToolApproval approval = read(approvalId == null ? "" : approvalId.toString());
        if (approval == null || !approval.pending()) {
            return Optional.empty();
        }
        return Optional.of(approval);
    }

    public void recordResume(String approvalId, String resumedWorkflowId) {
        if (approvalId == null || approvalId.isBlank() || resumedWorkflowId == null || resumedWorkflowId.isBlank()) {
            return;
        }

        String key = key(approvalId);
        Instant now = Instant.now(clock);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().put(key, "resumedWorkflowId", resumedWorkflowId);
                operations.opsForHash().put(key, "updatedAt", now.toString());
                operations.expire(key, WorkflowService.workflowTtl());
                return null;
            }
        });
    }

    public String replayMessage(ToolApproval approval, boolean approved) {
        String decision = approved ? "approved" : "rejected";
        String instruction = approved
                ? "The user approved the pending tool call. Continue the original request and run the approved tool if it is still needed."
                : "The user rejected the pending tool call. Continue without running that tool and explain the limitation if needed.";
        return """
                Continue this stock analysis workflow after human approval.

                Original workflow: %s
                Conversation: %s
                Approval id: %s
                Decision: %s
                Tool: %s
                Agent: %s
                Ticker: %s
                Arguments:
                %s

                %s
                """.formatted(
                approval.workflowId(),
                approval.conversationId(),
                approval.approvalId(),
                decision,
                approval.toolName(),
                approval.agentType(),
                approval.ticker(),
                approval.arguments(),
                instruction
        );
    }

    private ToolApproval createPendingApproval(
            String approvalId,
            String workflowId,
            String activeWorkflowId,
            String toolName,
            AgentType agentType,
            String ticker,
            String question,
            String arguments
    ) {
        Instant now = Instant.now(clock);
        Map<String, String> workflowFields = workflowService.workflowFields(workflowId);
        ToolApproval approval = new ToolApproval(
                approvalId,
                workflowId,
                activeWorkflowId,
                value(workflowFields, "userId"),
                value(workflowFields, "sessionId"),
                value(workflowFields, "conversationId"),
                toolName,
                agentType == null ? "" : agentType.name(),
                clean(ticker),
                clean(question),
                arguments,
                STATUS_PENDING,
                now,
                now,
                null,
                null
        );
        write(approval);
        recordPendingApproval(activeWorkflowId, approval);
        return approval;
    }

    private void recordPendingApproval(String workflowId, ToolApproval approval) {
        if (workflowId == null || workflowId.isBlank() || approval == null || !approval.pending()) {
            return;
        }

        String key = WorkflowService.workflowKey(workflowId);
        Instant now = Instant.now(clock);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().put(key, "pendingApprovalId", approval.approvalId());
                operations.opsForHash().put(key, "pendingApprovalToolName", approval.toolName());
                operations.opsForHash().put(key, "pendingApprovalStatus", approval.status());
                operations.opsForHash().put(key, "updatedAt", now.toString());
                operations.expire(key, WorkflowService.workflowTtl());
                return null;
            }
        });
    }

    private Set<String> approvalRequiredTools() {
        Set<String> toolNames = approvalRequiredTools.get();
        return toolNames == null ? Set.of() : toolNames;
    }

    private static boolean containsTool(Iterable<String> toolNames, String expectedToolName) {
        for (String toolName : toolNames) {
            if (expectedToolName.equals(cleanStatic(toolName))) {
                return true;
            }
        }
        return false;
    }

    private ToolApproval decide(String approvalId, String status) {
        ToolApproval existing = readRequired(approvalId);
        if (existing.approved() || existing.rejected()) {
            return existing;
        }

        Instant now = Instant.now(clock);
        String key = key(approvalId);
        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().put(key, "status", status);
                operations.opsForHash().put(key, "decidedAt", now.toString());
                operations.opsForHash().put(key, "updatedAt", now.toString());
                operations.expire(key, WorkflowService.workflowTtl());
                return null;
            }
        });
        ToolApproval decided = readRequired(approvalId);
        log.info(
                "workflow_approval_decided approvalId={} workflowId={} status={} toolName={}",
                decided.approvalId(),
                decided.workflowId(),
                decided.status(),
                decided.toolName()
        );
        return decided;
    }

    private ToolApproval read(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            return null;
        }

        Map<Object, Object> fields = redisTemplate.opsForHash().entries(key(approvalId));
        if (fields.isEmpty()) {
            return null;
        }
        return new ToolApproval(
                value(fields, "approvalId"),
                value(fields, "workflowId"),
                value(fields, "activeWorkflowId"),
                value(fields, "userId"),
                value(fields, "sessionId"),
                value(fields, "conversationId"),
                value(fields, "toolName"),
                value(fields, "agentType"),
                value(fields, "ticker"),
                value(fields, "question"),
                value(fields, "arguments"),
                value(fields, "status"),
                instant(value(fields, "createdAt")),
                instant(value(fields, "updatedAt")),
                instant(value(fields, "decidedAt")),
                value(fields, "resumedWorkflowId")
        );
    }

    private void write(ToolApproval approval) {
        String key = key(approval.approvalId());
        Map<String, String> fields = new LinkedHashMap<>();
        put(fields, "approvalId", approval.approvalId());
        put(fields, "workflowId", approval.workflowId());
        put(fields, "activeWorkflowId", approval.activeWorkflowId());
        put(fields, "userId", approval.userId());
        put(fields, "sessionId", approval.sessionId());
        put(fields, "conversationId", approval.conversationId());
        put(fields, "toolName", approval.toolName());
        put(fields, "agentType", approval.agentType());
        put(fields, "ticker", approval.ticker());
        put(fields, "question", approval.question());
        put(fields, "arguments", approval.arguments());
        put(fields, "status", approval.status());
        put(fields, "createdAt", approval.createdAt());
        put(fields, "updatedAt", approval.updatedAt());

        redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) throws DataAccessException {
                operations.opsForHash().putAll(key, fields);
                operations.expire(key, WorkflowService.workflowTtl());
                return null;
            }
        });
    }

    private String approvalId(String workflowId, String toolName, String arguments) {
        return hash(workflowId + "\n" + toolName + "\n" + arguments).substring(0, 32);
    }

    private String arguments(String ticker, String question) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("question", clean(question));
        values.put("ticker", clean(ticker).toUpperCase());
        return values.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private String hash(String value) {
        try {
            return HEX_FORMAT.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable.", ex);
        }
    }

    private static String key(String approvalId) {
        return KEY_PREFIX + approvalId;
    }

    private String value(Map<?, ?> fields, String name) {
        Object value = fields.get(name);
        return value == null ? "" : value.toString();
    }

    private Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }

    private void put(Map<String, String> fields, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            fields.put(key, value.toString());
        }
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanStatic(String value) {
        return value == null ? "" : value.trim();
    }
}
