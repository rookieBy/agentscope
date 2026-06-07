package io.agentscope.router.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Response for {@code GET /api/v1/routing/candidates} — bare list of all
 * registered {@code provider:model} names.
 */
public record RoutingCandidatesResponse(
        List<String> qualifiedNames,
        int count,
        Instant timestamp
) { }
