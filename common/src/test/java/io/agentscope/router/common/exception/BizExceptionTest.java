package io.agentscope.router.common.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BizExceptionTest {

    @Test
    void carriesErrorCodeAndMessage() {
        BizException ex = new BizException(ErrorCode.MISSING_TENANT_ID);
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.MISSING_TENANT_ID);
        assertThat(ex.getMessage()).isEqualTo(ErrorCode.MISSING_TENANT_ID.defaultMessage());
        assertThat(ex.getDetails()).isEmpty();
    }

    @Test
    void customMessage() {
        BizException ex = new BizException(ErrorCode.INVALID_TENANT_ID, "bad='x'");
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TENANT_ID);
        assertThat(ex.getMessage()).isEqualTo("bad='x'");
    }

    @Test
    void detailsIsImmutableCopy() {
        Map<String, Object> original = Map.of("k", "v");
        BizException ex = new BizException(ErrorCode.MODEL_NOT_FOUND, "msg", original);
        assertThat(ex.getDetails()).containsExactlyEntriesOf(original);
    }

    @Test
    void nullDetailsBecomeEmpty() {
        BizException ex = new BizException(ErrorCode.INTERNAL_ERROR, "x", null);
        assertThat(ex.getDetails()).isEmpty();
    }
}
