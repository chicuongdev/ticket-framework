package io.hrc.reconciliation;

import io.hrc.reconciliation.model.ReconciliationResult;

/**
 * Contract cho reconciliation metrics.
 *
 * <p>Inject {@link #NO_OP} khi không cần metrics thực.
 * Inject implementation của {@code hcr-observability} khi cần Prometheus/Micrometer.
 *
 * <p>Designed for extension: mỗi phương thức có Javadoc mô tả metric name và tag
 * để {@code MicrometerReconciliationMetrics} biết cần ghi gì.
 */
public interface ReconciliationMetrics {

    /**
     * Ghi nhận kết quả sau một reconciliation cycle hoàn chỉnh.
     *
     * <p>Metric: {@code hcr_reconciliation_run_total} (counter),
     * {@code hcr_reconciliation_duration_ms} (histogram),
     * {@code hcr_reconciliation_fixed_total} by case (counter).
     *
     * @param result tổng kết của cycle vừa chạy
     */
    void recordReconciliationRun(ReconciliationResult result);

    /**
     * Ghi nhận khi phát hiện lệch giữa Redis và DB (P3 only).
     *
     * <p>Metric: {@code hcr_inventory_mismatch_total} (counter, tag: resourceId),
     * {@code hcr_inventory_mismatch_delta} (histogram, tag: resourceId).
     *
     * @param resourceId resource bị lệch
     * @param delta      độ lệch = redisAvailable - dbAvailable
     */
    void recordInventoryMismatch(String resourceId, long delta);

    /**
     * Ghi nhận khi fix thành công một số case thuộc một loại cụ thể.
     *
     * <p>Metric: {@code hcr_reconciliation_fixed_total} (counter, tag: case).
     *
     * @param reconciliationCase loại case đã fix
     * @param count              số lượng đã fix trong lần này
     */
    void recordFixedByCase(ReconciliationCase reconciliationCase, int count);

    // -------------------------------------------------------------------------
    // NO_OP implementation — dùng khi chưa có observability module
    // -------------------------------------------------------------------------

    ReconciliationMetrics NO_OP = new ReconciliationMetrics() {
        @Override
        public void recordReconciliationRun(ReconciliationResult result) {}

        @Override
        public void recordInventoryMismatch(String resourceId, long delta) {}

        @Override
        public void recordFixedByCase(ReconciliationCase reconciliationCase, int count) {}
    };
}
