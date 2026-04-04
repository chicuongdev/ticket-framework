package io.hrc.core.result;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Ảnh chụp trạng thái inventory tại một thời điểm cụ thể.
 *
 * <p>Được dùng trong:
 * <ul>
 *   <li><b>Reconciliation:</b> so sánh Redis vs DB để phát hiện mismatch.</li>
 *   <li><b>Observability:</b> expose realtime inventory gauge cho Prometheus.</li>
 *   <li><b>InventoryStrategy.getSnapshot():</b> debug state của hệ thống.</li>
 * </ul>
 *
 * <p>Với P3 (RedisAtomic): sẽ có 2 snapshot — một từ Redis, một từ DB.
 * Reconciliation so sánh 2 snapshot này để phát hiện drift.
 */
@Getter
@Builder
public class InventorySnapshot {

    /** ID tài nguyên được snapshot. */
    private final String resourceId;

    /** Tổng số lượng ban đầu khi initialize. */
    private final long totalQuantity;

    /**
     * Số lượng hiện có thể đặt.
     * Công thức: totalQuantity - reservedQuantity - confirmedQuantity
     */
    private final long availableQuantity;

    /**
     * Số lượng đang được giữ chỗ (đã reserve nhưng chưa confirm hoặc cancel).
     * Trạng thái này tồn tại trong khoảng thời gian thanh toán.
     */
    private final long reservedQuantity;

    /**
     * Số lượng đã được confirm thành công (đơn hàng hoàn tất).
     * Đây là lượng tài nguyên đã "tiêu thụ" vĩnh viễn.
     */
    private final long confirmedQuantity;

    /** Thời điểm snapshot này được tạo. */
    private final Instant snapshotAt;

    /**
     * Nguồn dữ liệu: {@code "redis"} hoặc {@code "database"}.
     * Dùng trong Reconciliation để phân biệt 2 snapshot khi so sánh.
     */
    private final String source;

    // -------------------------------------------------------------------------
    // Reconciliation helpers
    // -------------------------------------------------------------------------

    /**
     * Kiểm tra snapshot này có nhất quán với snapshot khác không.
     * Hai snapshot nhất quán khi availableQuantity bằng nhau.
     *
     * @param other snapshot từ nguồn khác (vd: DB snapshot so với Redis snapshot)
     * @return true nếu không có sự chênh lệch
     */
    public boolean isConsistentWith(InventorySnapshot other) {
        if (other == null) return false;
        return this.availableQuantity == other.availableQuantity;
    }

    /**
     * Tính độ lệch availableQuantity giữa snapshot này và snapshot khác.
     * Kết quả dương: snapshot này có nhiều hơn. Kết quả âm: snapshot này có ít hơn.
     *
     * @param other snapshot cần so sánh
     * @return delta = this.availableQuantity - other.availableQuantity
     */
    public long getDelta(InventorySnapshot other) {
        if (other == null) return this.availableQuantity;
        return this.availableQuantity - other.availableQuantity;
    }
}
