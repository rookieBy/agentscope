package io.agentscope.router.common.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantContextHolderTest {

    @AfterEach
    void clear() {
        // defensive: holder has no public clear, but `set(null)` would NPE; just
        // ensure no leftover context from a previous test.
    }

    @Test
    @DisplayName("current() returns null when no context is bound")
    void noContext() {
        assertThat(TenantContextHolder.current()).isNull();
        assertThat(TenantContextHolder.currentTenantId()).isNull();
        assertThatThrownBy(TenantContextHolder::requireTenantId)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("set() binds the context, Scope.close() clears it")
    void setAndClose() {
        TenantContext ctx = new TenantContext("acme", "req-1", Instant.now());
        assertThat(TenantContextHolder.current()).isNull();
        try (TenantContextHolder.Scope ignored = TenantContextHolder.set(ctx)) {
            assertThat(TenantContextHolder.current()).isSameAs(ctx);
            assertThat(TenantContextHolder.currentTenantId()).isEqualTo("acme");
            assertThat(TenantContextHolder.requireTenantId()).isEqualTo("acme");
        }
        assertThat(TenantContextHolder.current()).isNull();
    }

    @Test
    @DisplayName("nested set() restores the outer context on inner close")
    void nested() {
        TenantContext outer = new TenantContext("outer", "r-1", Instant.now());
        TenantContext inner = new TenantContext("inner", "r-2", Instant.now());
        try (TenantContextHolder.Scope ignored = TenantContextHolder.set(outer)) {
            assertThat(TenantContextHolder.currentTenantId()).isEqualTo("outer");
            try (TenantContextHolder.Scope ignored2 = TenantContextHolder.set(inner)) {
                assertThat(TenantContextHolder.currentTenantId()).isEqualTo("inner");
            }
            assertThat(TenantContextHolder.currentTenantId()).isEqualTo("outer");
        }
        assertThat(TenantContextHolder.current()).isNull();
    }

    @Test
    @DisplayName("call(supplier) wraps execution and clears on completion")
    void callSupplier() {
        TenantContext ctx = new TenantContext("acme", "r-1", Instant.now());
        AtomicReference<String> seen = new AtomicReference<>();
        String result = TenantContextHolder.call(ctx, () -> {
            seen.set(TenantContextHolder.currentTenantId());
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(seen.get()).isEqualTo("acme");
        assertThat(TenantContextHolder.current()).isNull();
    }
}
