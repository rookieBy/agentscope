package io.agentscope.router.llm.core;

import io.agentscope.core.model.Model;

/**
 * Routing-aware wrapper around AgentScope's {@link Model} contract. Adds two
 * pieces of metadata needed by the routing layer:
 *
 * <ul>
 *   <li>{@link #provider()} — short provider id (e.g. "openai", "dashscope")</li>
 *   <li>{@link #health(String)} — per-tenant health snapshot for scoring</li>
 * </ul>
 *
 * <p>The full TTFT-based switching lives in {@code RoutingChatModel} (M3);
 * this interface exists so adapters and the registry can be written before
 * the routing logic is in place.
 */
public interface ChatModelBase extends Model {

    /** Provider id, e.g. "openai" / "dashscope" / "minimax". */
    String provider();

    /** Fully qualified {@code provider:model} name. */
    String qualifiedName();

    /** Snapshot of the health state of this model for the given tenant. */
    HealthSnapshot health(String tenantId);
}
