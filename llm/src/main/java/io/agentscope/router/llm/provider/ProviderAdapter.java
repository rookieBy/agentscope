package io.agentscope.router.llm.provider;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.router.llm.core.ChatModelBase;
import io.agentscope.router.llm.core.HealthSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Abstract base for provider adapters. Each adapter wraps a single concrete
 * AgentScope {@link Model} (created by the subclass) and adds:
 *
 * <ul>
 *   <li>a stable {@code provider} id and {@code provider:model} qualified name,</li>
 *   <li>a per-tenant health snapshot map (mutable; M3 writes here),</li>
 *   <li>delegating {@code stream()} / {@code getModelName()} calls.</li>
 * </ul>
 *
 * <p>The delegate {@link Model} is built lazily via the subclass hook
 * {@link #buildDelegate()} the first time a call is made. Subclass fields
 * (api-key, base-url, ...) must therefore be assigned before any method that
 * touches the delegate. The two-arg constructor that accepts a pre-built
 * delegate is kept for tests and for callers that want eager construction.
 */
public abstract class ProviderAdapter implements ChatModelBase {

    private static final Logger log = LoggerFactory.getLogger(ProviderAdapter.class);

    private final String modelName;
    private final String qualifiedName;
    private final Model eagerDelegate;
    private volatile Model delegate;
    private final ConcurrentHashMap<String, HealthSnapshot> healthByTenant = new ConcurrentHashMap<>();

    /** Convenience constructor: delegate is built lazily on first use. */
    protected ProviderAdapter(String modelName) {
        this(modelName, null);
    }

    /**
     * @param modelName     the model id (e.g. {@code "gpt-4o"})
     * @param eagerDelegate optional pre-built delegate. When {@code null} the
     *                      subclass's {@link #buildDelegate()} is invoked
     *                      lazily on first use.
     */
    protected ProviderAdapter(String modelName, Model eagerDelegate) {
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        this.qualifiedName = providerId() + ":" + modelName;
        this.eagerDelegate = eagerDelegate;
    }

    public abstract String providerId();

    /** Subclass hook for constructing the underlying AgentScope model. */
    protected abstract Model buildDelegate();

    private Model resolveDelegate() {
        Model d = delegate;
        if (d != null) return d;
        synchronized (this) {
            d = delegate;
            if (d != null) return d;
            if (eagerDelegate != null) {
                delegate = eagerDelegate;
                return eagerDelegate;
            }
            log.info("Building delegate lazily: {}", qualifiedName);
            Model built = buildDelegate();
            if (built == null) {
                throw new IllegalStateException(
                        "buildDelegate() returned null for " + qualifiedName);
            }
            delegate = built;
            return built;
        }
    }

    @Override
    public String provider() {
        return providerId();
    }

    @Override
    public String qualifiedName() {
        return qualifiedName;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return resolveDelegate().stream(messages, tools, options);
    }

    @Override
    public HealthSnapshot health(String tenantId) {
        if (tenantId == null) {
            return HealthSnapshot.empty(qualifiedName, null);
        }
        return healthByTenant.computeIfAbsent(tenantId,
                tid -> HealthSnapshot.empty(qualifiedName, tid));
    }

    /** M3 hook: record a successful call's TTFT for a tenant. */
    public void recordSuccess(String tenantId, long ttftMs) {
        if (log.isTraceEnabled()) {
            log.trace("recordSuccess tenant={} model={} ttftMs={}", tenantId, qualifiedName, ttftMs);
        }
    }

    /** M3 hook: record a failure for a tenant. */
    public void recordFailure(String tenantId, Throwable error) {
        if (log.isTraceEnabled()) {
            log.trace("recordFailure tenant={} model={} error={}", tenantId, qualifiedName,
                    error == null ? "null" : error.getClass().getSimpleName());
        }
    }
}
