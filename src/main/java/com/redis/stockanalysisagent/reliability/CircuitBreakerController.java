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
    private final ProviderFailureSimulationService failureSimulationService;

    public CircuitBreakerController(
            CircuitBreakerService circuitBreakerService,
            ProviderFailureSimulationService failureSimulationService
    ) {
        this.circuitBreakerService = circuitBreakerService;
        this.failureSimulationService = failureSimulationService;
    }

    @GetMapping
    public List<CircuitBreakerProviderStatus> statuses() {
        Map<Object, Object> simulations = failureSimulationService.states();
        return PROVIDERS.stream()
                .map(provider -> status(provider, simulations))
                .toList();
    }

    @GetMapping("/{providerId}")
    public CircuitBreakerState state(@PathVariable String providerId) {
        return circuitBreakerService.state(providerId);
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
        return status(provider, failureSimulationService.states());
    }

    private CircuitBreakerProviderStatus status(ProviderDescriptor provider, Map<Object, Object> simulations) {
        boolean simulationEnabled = "true".equalsIgnoreCase(
                String.valueOf(simulations.getOrDefault(provider.providerId(), "false"))
        );
        return new CircuitBreakerProviderStatus(
                provider.providerId(),
                provider.label(),
                circuitBreakerService.state(provider.providerId()),
                simulationEnabled
        );
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

    private record ProviderDescriptor(String providerId, String label) {
    }
}
