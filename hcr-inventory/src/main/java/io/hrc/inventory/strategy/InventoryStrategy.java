package io.hrc.inventory.strategy;

import io.hrc.core.enums.ConsistencyLevel;
import io.hrc.core.result.InventorySnapshot;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.metrics.InventoryMetrics;

import java.util.Map;

/**
 * Contract chuẩn mà tất cả inventory strategy phải tuân theo.
 *
 * <p>Saga Orchestrator chỉ phụ thuộc vào interface này — có thể đổi strategy
 * (P1 → P2 → P3) chỉ bằng cách thay đổi config yaml mà không cần sửa code Saga.
 *
 * <p>Các strategy implementation:
 * <ul>
 *   <li>P1 — {@link io.hrc.inventory.strategy.pessimistic.PessimisticLockStrategy}</li>
 *   <li>P2 — {@link io.hrc.inventory.strategy.optimistic.OptimisticLockStrategy}</li>
 *   <li>P3 — {@link io.hrc.inventory.strategy.redis.RedisAtomicStrategy}</li>
 * </ul>
 */
public interface InventoryStrategy {

    // =========================================================================
    // Core Operations — trái tim của mọi strategy
    // =========================================================================

    /**
     * Giữ chỗ atomic cho {@code quantity} đơn vị của {@code resourceId}.
     * Đảm bảo zero-oversell: không bao giờ để availableQuantity &lt; 0.
     *
     * @param resourceId ID tài nguyên cần giữ chỗ
     * @param requestId  ID của request (dùng cho idempotency ở tầng strategy)
     * @param quantity   số lượng muốn giữ
     * @return {@link ReservationResult#success} nếu thành công,
     *         {@link ReservationResult#insufficient} nếu không đủ hàng,
     *         {@link ReservationResult#error} nếu lỗi hệ thống
     */
    ReservationResult reserve(String resourceId, String requestId, int quantity);

    /**
     * Giải phóng inventory đã được giữ chỗ trước đó.
     * Đây là compensating action — được gọi khi payment fail hoặc order bị cancel.
     *
     * @param resourceId ID tài nguyên cần giải phóng
     * @param requestId  ID của request gốc đã reserve
     * @param quantity   số lượng cần giải phóng
     */
    void release(String resourceId, String requestId, int quantity);

    // =========================================================================
    // Query Operations
    // =========================================================================

    /** @return số lượng còn có thể đặt tại thời điểm gọi */
    long getAvailable(String resourceId);

    /** @return true nếu còn ít nhất 1 đơn vị */
    boolean isAvailable(String resourceId);

    /** @return true nếu còn đủ {@code quantity} đơn vị */
    boolean isAvailable(String resourceId, int quantity);

    /**
     * Trả về snapshot đầy đủ của inventory tại thời điểm gọi.
     * Dùng trong Reconciliation và Observability.
     *
     * @param resourceId ID tài nguyên cần snapshot
     * @return snapshot với source = "redis" hoặc "database" tùy strategy
     */
    InventorySnapshot getSnapshot(String resourceId);

    // =========================================================================
    // Management Operations
    // =========================================================================

    /**
     * Khởi tạo inventory mới cho một tài nguyên.
     * Nếu resourceId đã tồn tại → throw {@link IllegalStateException}.
     *
     * @param resourceId    ID tài nguyên
     * @param totalQuantity tổng số lượng ban đầu
     */
    void initialize(String resourceId, long totalQuantity);

    /**
     * Thêm tồn kho cho tài nguyên đã có.
     * Kích hoạt lại nếu trước đó đã DEPLETED.
     *
     * @param resourceId ID tài nguyên
     * @param quantity   số lượng thêm vào
     */
    void restock(String resourceId, long quantity);

    /**
     * Ngừng bán tài nguyên — mọi request reserve sau đó sẽ bị từ chối.
     * Admin gọi method này; để kích hoạt lại dùng {@link #restock}.
     */
    void deactivate(String resourceId);

    // =========================================================================
    // Bulk Operations — cho flash sale nhiều sản phẩm
    // =========================================================================

    /**
     * Reserve nhiều resource cùng lúc trong một atomic operation.
     * Để tránh deadlock (P1/P2): các key được lock theo thứ tự alphabet.
     *
     * @param requests map: resourceId → quantity
     * @return map: resourceId → ReservationResult
     */
    Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests);

    /**
     * Release nhiều resource cùng lúc — compensating action cho batch reserve.
     *
     * @param releases map: resourceId → quantity
     */
    void releaseBatch(Map<String, Integer> releases);

    // =========================================================================
    // Monitoring
    // =========================================================================

    /**
     * @param threshold ngưỡng cảnh báo
     * @return true nếu available &lt;= threshold
     */
    boolean isLowStock(String resourceId, long threshold);

    /**
     * Lấy metrics snapshot của strategy cho resourceId này.
     * Dùng trong Observability và InventoryInitializer.
     */
    InventoryMetrics getMetrics();

    /** @return tên strategy để gắn vào metrics tags: "pessimistic", "optimistic", "redis-atomic" */
    String getStrategyName();

    /** @return mức consistency mà strategy này cam kết */
    ConsistencyLevel getConsistencyLevel();
}
