package io.agentscope.router.business.tools;

import io.agentscope.core.tool.ContextStore;

/**
 * Dynamic {@link ContextStore} that hands out the currently-active
 * {@link ToolContext} to the AgentScope framework at tool-invocation time.
 *
 * <p>The framework's {@code ToolMethodInvoker} resolves non-{@code @ToolParam}
 * parameters by calling {@code ToolExecutionContext.get(parameterType)}. By
 * registering this store on the agent's {@code toolExecutionContext}, the
 * framework can hand our custom {@link ToolContext} POJO directly into the
 * tool method — no ThreadLocal, no executor-wrapping hack, no
 * {@code ScheduledExecutorService} propagation games.
 *
 * <p>Per-request isolation: the store reads from {@link MediaTools#currentRequest()},
 * a static field that {@code ChatAgentService} sets in {@code doOnSubscribe}
 * and clears in {@code doFinally}. The store's {@code get()} runs on whatever
 * thread the framework schedules the tool invocation on, but since the value
 * lives in a static field it survives the thread hop.
 */
public class RequestContextStore implements ContextStore {

    @Override
    public <T> T get(String name, Class<T> type) {
        return get(type);
    }

    @Override
    public <T> T get(Class<T> type) {
        if (type == null) {
            return null;
        }
        if (type.isAssignableFrom(ToolContext.class)) {
            ToolContext current = MediaTools.currentRequest();
            if (current == null) {
                return null;
            }
            return type == ToolContext.class ? type.cast(current) : null;
        }
        return null;
    }

    @Override
    public boolean contains(String name, Class<?> type) {
        return contains(type);
    }

    @Override
    public boolean contains(Class<?> type) {
        return type != null && type.isAssignableFrom(ToolContext.class);
    }
}
