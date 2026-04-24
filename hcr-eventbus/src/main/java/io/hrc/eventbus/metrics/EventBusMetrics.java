package io.hrc.eventbus.metrics;

/**
 * Contract để EventBus adapter ghi metrics.
 *
 * <p>Implementation thực tế là {@code MicrometerFrameworkMetrics} trong hcr-observability.
 * Fallback: {@link #NO_OP}.
 *
 * <p>Metric naming: {@code hcr_event_*}. Tag chính: {@code event_type}, {@code adapter}.
 */
public interface EventBusMetrics {

    /**
     * Ghi nhận sau khi publish event thành công lên broker.
     *
     * @param adapter tên adapter: "kafka", "rabbitmq", "redis-stream", "in-memory"
     */
    void recordEventPublished(String eventType, String adapter);

    /**
     * Ghi nhận sau khi consumer xử lý xong event.
     *
     * @param durationMs thời gian từ khi consumer nhận đến khi ack (ms)
     */
    void recordEventConsumed(String eventType, long durationMs);

    /** Ghi nhận khi consumer xử lý event thất bại (trước khi đưa vào DLQ). */
    void recordEventFailed(String eventType, String reason);

    EventBusMetrics NO_OP = new NoOpEventBusMetrics();

    class NoOpEventBusMetrics implements EventBusMetrics {
        @Override public void recordEventPublished(String t, String a) {}
        @Override public void recordEventConsumed(String t, long d) {}
        @Override public void recordEventFailed(String t, String r) {}
    }
}
