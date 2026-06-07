package io.agentscope.router.common.tenant;

import io.agentscope.router.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisKeyFactoryTest {

    @Nested
    @DisplayName("requireTenantId validation")
    class RequireTenantId {

        @Test
        @DisplayName("accepts valid tenant ids")
        void acceptsValid() {
            assertThat(RedisKeyFactory.requireTenantId("acme")).isEqualTo("acme");
            assertThat(RedisKeyFactory.requireTenantId("acme-corp_42")).isEqualTo("acme-corp_42");
            assertThat(RedisKeyFactory.requireTenantId("ABC_123")).isEqualTo("ABC_123");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "acme corp", "acme/corp", "acme.corp", "acme:corp",
                "acme#corp", "中文-租户"})
        @DisplayName("rejects malformed tenant ids")
        void rejectsMalformed(String value) {
            assertThatThrownBy(() -> RedisKeyFactory.requireTenantId(value))
                    .isInstanceOfAny(BizException.class, IllegalArgumentException.class);
        }

        @Test
        @DisplayName("isValid mirrors requireTenantId without throwing")
        void isValidCheck() {
            assertThat(RedisKeyFactory.isValid("acme")).isTrue();
            assertThat(RedisKeyFactory.isValid("acme corp")).isFalse();
            assertThat(RedisKeyFactory.isValid(null)).isFalse();
            assertThat(RedisKeyFactory.isValid("")).isFalse();
        }
    }

    @Nested
    @DisplayName("key layout")
    class KeyLayout {

        @Test
        @DisplayName("videoTask: t:{tenant}:video:task:{taskId}")
        void videoTask() {
            assertThat(RedisKeyFactory.videoTask("acme", "abc-123"))
                    .isEqualTo("t:acme:video:task:abc-123");
        }

        @Test
        @DisplayName("videoEventChannel: t:{tenant}:video:event:{taskId}")
        void videoEventChannel() {
            assertThat(RedisKeyFactory.videoEventChannel("acme", "abc-123"))
                    .isEqualTo("t:acme:video:event:abc-123");
        }

        @Test
        @DisplayName("routingCounter: t:{tenant}:routing:counter")
        void routingCounter() {
            assertThat(RedisKeyFactory.routingCounter("acme"))
                    .isEqualTo("t:acme:routing:counter");
        }

        @Test
        @DisplayName("agentSession: t:{tenant}:agent:session:{sid}")
        void agentSession() {
            assertThat(RedisKeyFactory.agentSession("acme", "s-1"))
                    .isEqualTo("t:acme:agent:session:s-1");
        }

        @Test
        @DisplayName("tenantConfig: t:{tenant}:config")
        void tenantConfig() {
            assertThat(RedisKeyFactory.tenantConfig("acme"))
                    .isEqualTo("t:acme:config");
        }

        @Test
        @DisplayName("every key embeds the tenant id (L1 isolation invariant)")
        void everyKeyEmbedsTenant() {
            String t = "tenant-a";
            assertThat(RedisKeyFactory.videoTask(t, "x")).startsWith("t:" + t + ":");
            assertThat(RedisKeyFactory.videoEventChannel(t, "x")).startsWith("t:" + t + ":");
            assertThat(RedisKeyFactory.routingCounter(t)).startsWith("t:" + t + ":");
            assertThat(RedisKeyFactory.agentSession(t, "s")).startsWith("t:" + t + ":");
            assertThat(RedisKeyFactory.tenantConfig(t)).startsWith("t:" + t + ":");
        }
    }

    @Nested
    @DisplayName("isolation between tenants")
    class Isolation {

        @Test
        @DisplayName("two tenants generate disjoint keys for the same taskId")
        void disjointKeys() {
            String a = RedisKeyFactory.videoTask("tenant-a", "task-1");
            String b = RedisKeyFactory.videoTask("tenant-b", "task-1");
            assertThat(a).isNotEqualTo(b);
            assertThat(a).contains(":tenant-a:");
            assertThat(b).contains(":tenant-b:");
        }
    }
}
