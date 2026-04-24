package io.hrc.saga.metrics;

/**
 * Contract để Saga orchestrator ghi metrics.
 *
 * <p>Implementation thực tế là {@code MicrometerFrameworkMetrics} trong hcr-observability
 * (extends {@code SagaMetrics}). Nếu Micrometer không có trên classpath, framework
 * dùng {@link #NO_OP}.
 *
 * <p>Metric naming: {@code hcr_saga_*}. Tag chính: {@code resource_id}.
 */
public interface SagaMetrics {

    /** Ghi nhận khi saga transaction bắt đầu (sau khi pass validation). */
    void recordSagaStarted(String resourceId);

    /**
     * Ghi nhận khi saga hoàn thành thành công (order CONFIRMED).
     *
     * @param durationMs thời gian từ khi saga bắt đầu đến khi CONFIRMED (ms)
     */
    void recordSagaConfirmed(String resourceId, long durationMs);

    /**
     * Ghi nhận khi saga bị huỷ (order CANCELLED).
     *
     * @param reason lý do huỷ — thường là FailureReason.name() hoặc custom string
     */
    void recordSagaCancelled(String resourceId, String reason);

    /** Ghi nhận khi saga đã rollback thành công (compensation hoàn tất). */
    void recordSagaCompensated(String resourceId, String reason);

    SagaMetrics NO_OP = new NoOpSagaMetrics();

    class NoOpSagaMetrics implements SagaMetrics {
        @Override public void recordSagaStarted(String r) {}
        @Override public void recordSagaConfirmed(String r, long d) {}
        @Override public void recordSagaCancelled(String r, String reason) {}
        @Override public void recordSagaCompensated(String r, String reason) {}
    }
}
