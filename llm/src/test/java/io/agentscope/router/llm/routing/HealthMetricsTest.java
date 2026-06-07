package io.agentscope.router.llm.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HealthMetricsTest {

    private static HealthMetrics fresh() {
        return new HealthMetrics("acme", "minimax:MiniMax-M3",
                /*windowSize*/ 50, /*emaAlpha*/ 0.3,
                /*maxConsecutiveFailures*/ 3, /*cooldownMs*/ 1_000L);
    }

    @Nested
    @DisplayName("score()")
    class Score {

        @Test
        @DisplayName("starts at ~0.5 (recency penalty on unobserved model)")
        void freshScoreIsRecencyPenalized() {
            HealthMetrics m = fresh();
            // never observed → recencyFactor=0.5; ttftEma=0, errorRateEma=0
            //   speed=1.0, success=1.0, penalty=1.0, recency=0.5  → 0.5
            assertThat(m.score()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        }

        @Test
        @DisplayName("successes lift score above baseline")
        void successesImproveScore() {
            HealthMetrics m = fresh();
            for (int i = 0; i < 5; i++) m.recordSuccess(200L);
            assertThat(m.score()).isGreaterThan(0.5);
            // speed = 1/(1+0.2) = 0.833..., success=1, penalty=1, recency=1 → ~0.833
            assertThat(m.score()).isCloseTo(0.833, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("failures reduce score via success term and consecutive-fail penalty")
        void failuresReduceScore() {
            HealthMetrics m = fresh();
            m.recordSuccess(100L);
            m.recordSuccess(100L);
            double afterSuccesses = m.score();
            m.recordFailure();
            m.recordFailure();
            assertThat(m.score()).isLessThan(afterSuccesses);
        }
    }

    @Nested
    @DisplayName("isAvailable()")
    class Availability {

        @Test
        @DisplayName("available initially and after a single failure")
        void availableForIsolatedFailures() {
            HealthMetrics m = fresh();
            long now = System.currentTimeMillis();
            assertThat(m.isAvailable(now)).isTrue();
            m.recordFailure();
            assertThat(m.isAvailable(now)).isTrue();
        }

        @Test
        @DisplayName("unavailable when consecutive failures hit the max")
        void unavailableAfterThreshold() {
            HealthMetrics m = fresh();
            m.recordFailure();
            m.recordFailure();
            m.recordFailure();
            long now = System.currentTimeMillis();
            assertThat(m.isAvailable(now)).isFalse();
            assertThat(m.cooldownRemainingMs(now)).isPositive();
        }

        @Test
        @DisplayName("cooldown timer elapses; model only returns after a success resets the counter")
        void cooldownElapsesButCounterMustReset() throws InterruptedException {
            HealthMetrics m = new HealthMetrics("acme", "minimax:MiniMax-M3",
                    50, 0.3, 3, /*cooldownMs*/ 50L);
            m.recordFailure();
            m.recordFailure();
            m.recordFailure();
            long now = System.currentTimeMillis();
            assertThat(m.isAvailable(now)).isFalse();
            assertThat(m.cooldownRemainingMs(now)).isPositive();
            Thread.sleep(80L);
            // cooldown is over, but consec-failure counter is still at the threshold,
            // so the model remains unavailable until a success resets it.
            assertThat(m.isAvailable(System.currentTimeMillis())).isFalse();
            m.recordSuccess(50L);
            assertThat(m.isAvailable(System.currentTimeMillis())).isTrue();
        }

        @Test
        @DisplayName("consecutive failure counter resets after a success")
        void counterResetsOnSuccess() {
            HealthMetrics m = fresh();
            m.recordFailure();
            m.recordFailure();
            m.recordSuccess(50L);
            assertThat(m.consecutiveFailures()).isEqualTo(0);
            assertThat(m.isAvailable(System.currentTimeMillis())).isTrue();
        }
    }

    @Nested
    @DisplayName("EMA")
    class Ema {

        @Test
        @DisplayName("first sample seeds the EMA exactly")
        void firstSampleSeeds() {
            HealthMetrics m = fresh();
            m.recordSuccess(700L);
            assertThat(m.ttftEma()).isEqualTo(700.0);
        }

        @Test
        @DisplayName("subsequent samples blend via alpha")
        void subsequentSamplesBlend() {
            HealthMetrics m = fresh();
            m.recordSuccess(1_000L);
            m.recordSuccess(0L);
            // 0.3*0 + 0.7*1000 = 700
            assertThat(m.ttftEma()).isCloseTo(700.0, org.assertj.core.data.Offset.offset(0.001));
        }
    }
}
