package io.hrc.observability.metrics;

/**
 * Tham chiếu tên metric và tag cho nhóm Saga.
 *
 * <p>Metrics thực tế được ghi bởi
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics}.
 *
 * <pre>
 * hcr_saga_started_total           — counter  — tags: resource_id
 * hcr_saga_duration_ms             — timer    — tags: resource_id, outcome (confirmed)
 * hcr_saga_cancelled_total         — counter  — tags: resource_id, reason
 * hcr_saga_compensated_total       — counter  — tags: resource_id, reason
 * </pre>
 */
public final class SagaMetricsCollector {
    private SagaMetricsCollector() {}
}
