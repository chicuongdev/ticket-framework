package io.hrc.autoconfigure.filter;

/**
 * Re-export marker — implementation thực tế ở {@link io.hrc.gateway.filter.CorrelationIdFilter}.
 *
 * <p>HcrAutoConfiguration đăng ký {@code io.hrc.gateway.filter.CorrelationIdFilter}
 * như là một FilterRegistrationBean với {@code Ordered.HIGHEST_PRECEDENCE}.
 * Class này chỉ tồn tại để giữ package structure nhất quán.
 */
public final class CorrelationIdFilter {
    private CorrelationIdFilter() {}
}
