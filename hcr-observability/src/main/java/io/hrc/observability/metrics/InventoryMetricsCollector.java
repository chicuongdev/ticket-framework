package io.hrc.observability.metrics;

/**
 * Tham chiếu tên metric và tag cho nhóm Inventory.
 *
 * <p>Metrics thực tế được ghi bởi
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics}.
 * Class này đóng vai trò tài liệu — liệt kê toàn bộ metric name và tag
 * để developer biết cần query gì trong Prometheus/Grafana.
 *
 * <pre>
 * hcr_reservation_attempts_total   — counter  — tags: resource_id, strategy
 * hcr_reservation_duration_ms      — timer    — tags: resource_id, strategy, outcome
 * hcr_reservation_failures_total   — counter  — tags: resource_id, strategy, reason
 * hcr_release_total                — counter  — tags: resource_id, strategy
 * hcr_oversell_prevented_total     — counter  — tags: resource_id
 * hcr_low_stock_events_total       — counter  — tags: resource_id
 * hcr_depleted_events_total        — counter  — tags: resource_id
 * hcr_inventory_available          — gauge    — tags: resource_id
 * </pre>
 */
public final class InventoryMetricsCollector {
    private InventoryMetricsCollector() {}
}
