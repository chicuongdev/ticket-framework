package io.hrc.observability.metrics;

/**
 * Tham chiếu tên metric và tag cho nhóm Event Bus.
 *
 * <p>Metrics thực tế được ghi bởi
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics}.
 *
 * <pre>
 * hcr_event_published_total        — counter  — tags: event_type, adapter
 * hcr_event_consumed_duration_ms   — timer    — tags: event_type
 * hcr_event_failed_total           — counter  — tags: event_type, reason
 * </pre>
 */
public final class EventBusMetricsCollector {
    private EventBusMetricsCollector() {}
}
