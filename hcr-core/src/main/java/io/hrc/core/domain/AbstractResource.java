package io.hrc.core.domain;

import io.hrc.core.enums.ResourceStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Base class cho mọi "tài nguyên có giới hạn" trong hệ thống.
 * Developer PHẢI extend class này — không được dùng trực tiếp.
 *
 * <p><b>Framework quản lý:</b> resourceId, totalQuantity, availableQuantity,
 * status, createdAt, updatedAt.
 *
 * <p><b>Developer tự thêm:</b> các field nghiệp vụ riêng (vd: concertName,
 * venue, price...). Class con nên thêm {@code @Entity} và {@code @Table} nếu dùng JPA.
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "concert_tickets")
 * public class ConcertTicket extends AbstractResource {
 *     private String concertName;
 *     private String venue;
 *     private BigDecimal price;
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
public abstract class AbstractResource {

    /** Định danh duy nhất của tài nguyên. */
    private String resourceId;

    /** Tổng số lượng ban đầu khi initialize — không thay đổi sau đó. */
    private long totalQuantity;

    /**
     * Số lượng còn có thể đặt tại thời điểm hiện tại.
     * <b>Lưu ý P3:</b> field này trong DB có thể lag sau Redis — đọc từ Redis nếu cần realtime.
     */
    private long availableQuantity;

    /** Trạng thái hiện tại của tài nguyên. */
    private ResourceStatus status;

    /** Thời điểm tạo tài nguyên trong hệ thống. */
    private Instant createdAt;

    /** Thời điểm cập nhật gần nhất. */
    private Instant updatedAt;

    protected AbstractResource(String resourceId, long totalQuantity) {
        this.resourceId = resourceId;
        this.totalQuantity = totalQuantity;
        this.availableQuantity = totalQuantity;
        this.status = ResourceStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /** @return true nếu còn ít nhất 1 đơn vị có thể đặt. */
    public boolean isAvailable() {
        return status.isAcceptingOrders() && availableQuantity > 0;
    }

    /**
     * @param threshold ngưỡng cảnh báo (vd: 10 → cảnh báo khi còn ≤ 10 đơn vị)
     * @return true nếu số lượng còn lại dưới hoặc bằng ngưỡng
     */
    public boolean isLowStock(long threshold) {
        return availableQuantity <= threshold;
    }

    /** @return true nếu không còn đơn vị nào có thể đặt. */
    public boolean isDepleted() {
        return availableQuantity <= 0;
    }

    // -------------------------------------------------------------------------
    // Validation hook — developer override để thêm business rule
    // -------------------------------------------------------------------------

    /**
     * Hook cho developer override để validate nghiệp vụ riêng.
     * Framework gọi method này khi initialize tài nguyên.
     * Mặc định: không làm gì (pass-through).
     */
    public void validate() {
        // no-op — developer override để thêm rule
    }

    // -------------------------------------------------------------------------
    // Internal state transitions — chỉ InventoryStrategy gọi, không gọi trực tiếp
    // -------------------------------------------------------------------------

    void markLowStock() {
        this.status = ResourceStatus.LOW_STOCK;
        this.updatedAt = Instant.now();
    }

    void markDepleted() {
        this.status = ResourceStatus.DEPLETED;
        this.updatedAt = Instant.now();
    }

    void markDeactivated() {
        this.status = ResourceStatus.DEACTIVATED;
        this.updatedAt = Instant.now();
    }

    void markActive() {
        this.status = ResourceStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }
}
