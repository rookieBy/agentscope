package io.agentscope.router.llm.core;

import io.agentscope.router.common.exception.BizException;
import io.agentscope.router.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory registry of {@link ChatModelBase} instances, indexed by
 * {@code qualifiedName()} ("provider:model"). Populated at startup by
 * {@code AgentScopeRouterAutoConfiguration}.
 *
 * <p>Thread-safe for concurrent reads; writes only happen at startup.
 */
@Component("modelRegistry")
public class ModelRegistry {

    private final Map<String, ChatModelBase> byQualifiedName = new ConcurrentHashMap<>();

    public void register(ChatModelBase model) {
        if (model == null) {
            throw new IllegalArgumentException("model is null");
        }
        byQualifiedName.put(model.qualifiedName(), model);
    }

    public Optional<ChatModelBase> find(String qualifiedName) {
        if (qualifiedName == null) return Optional.empty();
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    public ChatModelBase getOrThrow(String qualifiedName) {
        return find(qualifiedName).orElseThrow(() ->
                new BizException(ErrorCode.MODEL_NOT_FOUND,
                        "No model registered with qualifiedName='" + qualifiedName + "'",
                        Map.of("available", byQualifiedName.keySet())));
    }

    public Collection<ChatModelBase> all() {
        return Collections.unmodifiableCollection(byQualifiedName.values());
    }

    public List<String> qualifiedNames() {
        return byQualifiedName.keySet().stream().sorted().collect(Collectors.toUnmodifiableList());
    }

    public List<ChatModelBase> forProvider(String provider) {
        return byQualifiedName.values().stream()
                .filter(m -> provider != null && provider.equalsIgnoreCase(m.provider()))
                .collect(Collectors.toUnmodifiableList());
    }

    /** Snapshot of the registry, ordered by qualified name. */
    public Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        byQualifiedName.values().stream()
                .sorted((a, b) -> a.qualifiedName().compareTo(b.qualifiedName()))
                .forEach(m -> out.put(m.qualifiedName(), m.provider()));
        return out;
    }

    public boolean isEmpty() {
        return byQualifiedName.isEmpty();
    }
}
