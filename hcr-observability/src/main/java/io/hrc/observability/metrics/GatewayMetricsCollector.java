package io.hrc.observability.metrics;

/**
 * Tham chiếu tên metric và tag cho nhóm Gateway.
 *
 * <p>Metrics thực tế được ghi bởi
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics}.
 *
 * <pre>
 * hcr_request_received_total       — counter  — tags: endpoint
 * hcr_request_duration_ms          — timer    — tags: endpoint, outcome
 * hcr_request_rejected_total       — counter  — tags: endpoint, reason
 * hcr_idempotency_hit_total        — counter  — tags: endpoint
 * </pre>
 *
 * <p>Giá trị của tag {@code reason} trong {@code hcr_request_rejected_total}:
 * {@code "rate_limit"}, {@code "validation"}, {@code "circuit_breaker"}.
 */
public final class GatewayMetricsCollector {
    private GatewayMetricsCollector() {}
}
