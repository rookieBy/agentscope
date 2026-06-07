package io.agentscope.router.api.dto;

import io.agentscope.router.llm.core.HealthSnapshot;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /api/v1/routing/status} — per-tenant health view
 * of the registry's candidate models.
 */
public record RoutingStatusResponse(
        String tenantId,
        int candidateCount,
        List<HealthSnapshot> candidates,
        Instant timestamp
) { }
