package io.agentscope.router.api.controller;

import io.agentscope.router.api.dto.RoutingCandidatesResponse;
import io.agentscope.router.api.dto.RoutingStatusResponse;
import io.agentscope.router.llm.routing.HealthScoreService;
import io.agentscope.router.common.tenant.TenantContextHolder;
import io.agentscope.router.llm.core.ModelRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Read-only routing introspection endpoints. All routes are tenant-scoped
 * (read {@code X-Tenant-Id} via {@link TenantContextHolder}).
 */
@RestController
@RequestMapping("/api/v1/routing")
public class RoutingController {

    private final ModelRegistry registry;
    private final HealthScoreService health;

    public RoutingController(ModelRegistry registry, HealthScoreService health) {
        this.registry = registry;
        this.health = health;
    }

    @GetMapping("/candidates")
    public RoutingCandidatesResponse candidates() {
        List<String> names = registry.qualifiedNames();
        return new RoutingCandidatesResponse(names, names.size(), Instant.now());
    }

    @GetMapping("/status")
    public RoutingStatusResponse status() {
        String tenantId = TenantContextHolder.currentTenantId();
        var snap = health.snapshotFor(tenantId, registry.qualifiedNames());
        return new RoutingStatusResponse(tenantId, snap.size(), snap, Instant.now());
    }
}
