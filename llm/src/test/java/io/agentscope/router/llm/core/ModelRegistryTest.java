package io.agentscope.router.llm.core;

import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.message.Msg;
import io.agentscope.router.llm.provider.ProviderAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRegistryTest {

    private ModelRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ModelRegistry();
    }

    @Test
    void registerAndFind() {
        ChatModelBase m = stub("dashscope", "qwen-plus");
        registry.register(m);
        assertThat(registry.find("dashscope:qwen-plus")).contains(m);
        assertThat(registry.find("missing")).isEmpty();
    }

    @Test
    void getOrThrow() {
        registry.register(stub("openai", "gpt-4o"));
        ChatModelBase m = registry.getOrThrow("openai:gpt-4o");
        assertThat(m.qualifiedName()).isEqualTo("openai:gpt-4o");
        assertThatThrownBy(() -> registry.getOrThrow("nope"))
                .isInstanceOf(io.agentscope.router.common.exception.BizException.class);
    }

    @Test
    void allAndForProvider() {
        registry.register(stub("openai", "gpt-4o"));
        registry.register(stub("dashscope", "qwen-plus"));
        registry.register(stub("dashscope", "qwen-max"));

        assertThat(registry.all()).hasSize(3);
        assertThat(registry.forProvider("dashscope")).hasSize(2);
        assertThat(registry.forProvider("OPENAI")).hasSize(1);
        assertThat(registry.forProvider("nope")).isEmpty();
    }

    @Test
    void qualifiedNamesAreSorted() {
        registry.register(stub("z", "zzz"));
        registry.register(stub("a", "aaa"));
        registry.register(stub("m", "mmm"));
        assertThat(registry.qualifiedNames()).containsExactly("a:aaa", "m:mmm", "z:zzz");
    }

    @Test
    void describeYieldsOrderedMap() {
        registry.register(stub("z", "zzz"));
        registry.register(stub("a", "aaa"));
        var desc = registry.describe();
        assertThat(desc.keySet()).containsExactly("a:aaa", "z:zzz");
        assertThat(desc.get("a:aaa")).isEqualTo("a");
    }

    @Test
    void healthIsEmptyPerTenant() {
        ChatModelBase m = stub("dashscope", "qwen-plus");
        registry.register(m);
        HealthSnapshot s = registry.find("dashscope:qwen-plus").orElseThrow().health("acme");
        assertThat(s.tenantId()).isEqualTo("acme");
        assertThat(s.modelName()).isEqualTo("dashscope:qwen-plus");
        assertThat(s.score()).isEqualTo(1.0);
    }

    private static ChatModelBase stub(String provider, String model) {
        return new ProviderAdapter(model, new NoopModel()) {
            @Override public String providerId() { return provider; }
            @Override protected io.agentscope.core.model.Model buildDelegate() { return new NoopModel(); }
        };
    }

    private static final class NoopModel implements io.agentscope.core.model.Model {
        @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            return Flux.empty();
        }
        @Override public String getModelName() { return ""; }
    }
}
