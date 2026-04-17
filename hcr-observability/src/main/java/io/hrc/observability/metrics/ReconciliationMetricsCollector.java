package io.hrc.observability.metrics;

/**
 * Tham chiếu tên metric và tag cho nhóm Reconciliation.
 *
 * <p>Metrics thực tế được ghi bởi
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics}.
 *
 * <pre>
 * hcr_reconciliation_run_total     — counter  — tags: has_errors
 * hcr_reconciliation_duration_ms   — timer    — (no tags)
 * hcr_reconciliation_fixed_total   — counter  — tags: case
 * hcr_inventory_mismatch_total     — counter  — tags: resource_id
 * hcr_inventory_mismatch_delta     — summary  — tags: resource_id
 * </pre>
 */
public final class ReconciliationMetricsCollector {
    private ReconciliationMetricsCollector() {}
}
