package io.agentscope.router.common.tenant;

import java.util.function.Supplier;

/**
 * Static accessor for the current request's {@link TenantContext}, held in a
 * {@link ThreadLocal}. Inspired by SLF4J's MDC, but typed.
 *
 * <p>Usage: at the top of a request handler wrap a block in
 * {@code try (var s = TenantContextHolder.set(ctx)) { ... }}. The context is
 * automatically cleared on block exit.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CTX = new ThreadLocal<>();

    private TenantContextHolder() {}

    public static TenantContext current() {
        return CTX.get();
    }

    public static String currentTenantId() {
        TenantContext c = CTX.get();
        return c == null ? null : c.tenantId();
    }

    public static String requireTenantId() {
        String tid = currentTenantId();
        if (tid == null) {
            throw new IllegalStateException(
                    "No TenantContext bound. Was TenantContextFilter applied to this request?");
        }
        return tid;
    }

    public static Scope set(TenantContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("TenantContext is null");
        }
        TenantContext previous = CTX.get();
        CTX.set(ctx);
        return () -> {
            if (previous == null) {
                CTX.remove();
            } else {
                CTX.set(previous);
            }
        };
    }

    /** Run {@code supplier} with the given context bound, restoring on exit. */
    public static <T> T call(TenantContext ctx, Supplier<T> supplier) {
        try (Scope ignored = set(ctx)) {
            return supplier.get();
        }
    }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
