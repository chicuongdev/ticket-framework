package io.hrc.inventory.metrics;

import io.hrc.core.enums.FailureReason;

/**
 * Contract để các strategy ghi metrics.
 * Được inject vào tất cả strategy — mọi thao tác inventory đều được track tự động.
 *
 * <p>Implementation thực tế là {@code MicrometerInventoryMetrics} trong hcr-observability.
 * Nếu không có implementation được đăng ký, framework dùng {@link NoOpInventoryMetrics}.
 */
public interface InventoryMetrics {

    /** Ghi nhận một lần thử reserve (kể cả thất bại). */
    void recordReserveAttempt(String resourceId, String strategy);

    /**
     * Ghi nhận reserve thành công + thời gian xử lý.
     *
     * @param durationMs thời gian từ lúc nhận request đến khi trả về result (ms)
     */
    void recordReserveSuccess(String resourceId, String strategy, long durationMs);

    /**
     * Ghi nhận reserve thất bại theo lý do.
     *
     * @param reason {@link FailureReason#INSUFFICIENT_INVENTORY} hoặc {@link FailureReason#SYSTEM_ERROR}
     */
    void recordReserveFailure(String resourceId, String strategy, FailureReason reason);

    /** Ghi nhận một lần release thành công. */
    void recordReleaseSuccess(String resourceId, String strategy);

    /**
     * Ghi nhận số lần framework ngăn chặn được oversell.
     * Chỉ tăng khi request bị từ chối do không đủ inventory (không phải do lỗi khác).
     */
    void recordOversellPrevented(String resourceId);

    /** Ghi nhận khi available xuống dưới ngưỡng low stock. */
    void recordLowStock(String resourceId);

    /** Ghi nhận khi available = 0. */
    void recordDepleted(String resourceId);

    /**
     * Cập nhật Gauge realtime của available inventory.
     * Được gọi sau mỗi reserve/release thành công.
     *
     * @param available số lượng còn lại hiện tại
     */
    void updateAvailableGauge(String resourceId, long available);

    // -------------------------------------------------------------------------
    // No-op implementation — dùng khi không có Micrometer trên classpath
    // -------------------------------------------------------------------------

    /** Metrics no-op — không làm gì. Dùng cho test và khi không cần observability. */
    InventoryMetrics NO_OP = new NoOpInventoryMetrics();

    class NoOpInventoryMetrics implements InventoryMetrics {
        @Override public void recordReserveAttempt(String r, String s) {}
        @Override public void recordReserveSuccess(String r, String s, long d) {}
        @Override public void recordReserveFailure(String r, String s, FailureReason f) {}
        @Override public void recordReleaseSuccess(String r, String s) {}
        @Override public void recordOversellPrevented(String r) {}
        @Override public void recordLowStock(String r) {}
        @Override public void recordDepleted(String r) {}
        @Override public void updateAvailableGauge(String r, long a) {}
    }
}
