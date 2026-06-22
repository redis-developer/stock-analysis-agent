package com.redis.stockanalysisagent.reliability;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/circuit-breakers")
public class CircuitBreakerController {

    private static final List<ProviderDescriptor> PROVIDERS = List.of(
            new ProviderDescriptor("twelve-data", "Twelve Data"),
            new ProviderDescriptor("sec", "SEC"),
            new ProviderDescriptor("tavily", "Tavily"),
            new ProviderDescriptor("lang-cache", "LangCache"),
            new ProviderDescriptor("agent-memory", "Agent Memory")
    );

    private final CircuitBreakerService circuitBreakerService;
    private final ProviderCapacityService providerCapacityService;
    private final ProviderFailureSimulationService failureSimulationService;
    private final ProviderLatencySimulationService latencySimulationService;
    private final ProviderLatencySimulationProperties latencySimulationProperties;
    private final ProviderDeadLetterService deadLetterService;

    public CircuitBreakerController(
            CircuitBreakerService circuitBreakerService,
            ProviderCapacityService providerCapacityService,
            ProviderFailureSimulationService failureSimulationService,
            ProviderLatencySimulationService latencySimulationService,
            ProviderLatencySimulationProperties latencySimulationProperties,
            ProviderDeadLetterService deadLetterService
    ) {
        this.circuitBreakerService = circuitBreakerService;
        this.providerCapacityService = providerCapacityService;
        this.failureSimulationService = failureSimulationService;
        this.latencySimulationService = latencySimulationService;
        this.latencySimulationProperties = latencySimulationProperties;
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public List<CircuitBreakerProviderStatus> statuses() {
        Map<Object, Object> simulations = failureSimulationService.states();
        Map<Object, Object> latencySimulations = latencySimulationService.states();
        return PROVIDERS.stream()
                .map(provider -> status(provider, simulations, latencySimulations))
                .toList();
    }

    @GetMapping("/{providerId}")
    public CircuitBreakerState state(@PathVariable String providerId) {
        return circuitBreakerService.state(providerId);
    }

    @GetMapping("/dead-letter")
    public List<ProviderFailureRecord> deadLetter() {
        return deadLetterService.recent();
    }

    @PostMapping("/{providerId}/open")
    public CircuitBreakerState open(
            @PathVariable String providerId,
            @RequestBody(required = false) CircuitBreakerUpdateRequest request
    ) {
        return circuitBreakerService.open(providerId, request != null ? request.reason() : null);
    }

    @PostMapping("/{providerId}/close")
    public CircuitBreakerState close(@PathVariable String providerId) {
        return circuitBreakerService.close(providerId);
    }

    @PostMapping("/{providerId}/simulation")
    public CircuitBreakerProviderStatus simulate(
            @PathVariable String providerId,
            @RequestBody CircuitBreakerSimulationRequest request
    ) {
        ProviderDescriptor provider = provider(providerId);
        failureSimulationService.setEnabled(provider.providerId(), request != null && request.enabled());
        return status(provider, failureSimulationService.states(), latencySimulationService.states());
    }

    @PostMapping("/{providerId}/latency")
    public CircuitBreakerProviderStatus latency(
            @PathVariable String providerId,
            @RequestBody CircuitBreakerLatencySimulationRequest request
    ) {
        ProviderDescriptor provider = provider(providerId);
        latencySimulationService.setDelay(
                provider.providerId(),
                request != null && request.enabled() ? latencySimulationProperties.getDefaultDelay() : null
        );
        return status(provider, failureSimulationService.states(), latencySimulationService.states());
    }

    private CircuitBreakerProviderStatus status(
            ProviderDescriptor provider,
            Map<Object, Object> simulations,
            Map<Object, Object> latencySimulations
    ) {
        boolean simulationEnabled = "true".equalsIgnoreCase(
                String.valueOf(simulations.getOrDefault(provider.providerId(), "false"))
        );
        return new CircuitBreakerProviderStatus(
                provider.providerId(),
                provider.label(),
                circuitBreakerService.state(provider.providerId()),
                providerCapacityService.state(provider.providerId()),
                latencySimulationMs(latencySimulations.get(provider.providerId())),
                simulationEnabled
        );
    }

    private long latencySimulationMs(Object value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.toString()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private ProviderDescriptor provider(String providerId) {
        String normalized = CircuitBreakerService.normalizeProviderId(providerId);
        return PROVIDERS.stream()
                .filter(provider -> provider.providerId().equals(normalized))
                .findFirst()
                .orElse(new ProviderDescriptor(normalized, normalized));
    }

    public record CircuitBreakerUpdateRequest(String reason) {
    }

    public record CircuitBreakerSimulationRequest(boolean enabled) {
    }

    public record CircuitBreakerLatencySimulationRequest(boolean enabled) {
    }

    private record ProviderDescriptor(String providerId, String label) {
    }
}
