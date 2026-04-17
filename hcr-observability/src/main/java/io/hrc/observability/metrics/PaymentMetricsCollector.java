package io.hrc.observability.metrics;

/**
 * Tham chiếu tên metric và tag cho nhóm Payment.
 *
 * <p>Metrics thực tế được ghi bởi
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics}.
 *
 * <pre>
 * hcr_payment_attempts_total       — counter  — tags: gateway
 * hcr_payment_duration_ms          — timer    — tags: gateway, status
 * hcr_payment_failures_total       — counter  — tags: gateway, error_code
 * hcr_payment_timeouts_total       — counter  — tags: gateway
 * hcr_payment_unknown_total        — counter  — tags: gateway
 * </pre>
 */
public final class PaymentMetricsCollector {
    private PaymentMetricsCollector() {}
}
