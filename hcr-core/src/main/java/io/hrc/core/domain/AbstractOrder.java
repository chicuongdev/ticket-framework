package io.hrc.core.domain;

import io.hrc.core.enums.FailureReason;
import io.hrc.core.enums.OrderStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Base class cho một "yêu cầu đặt tài nguyên" trong hệ thống.
 * Developer PHẢI extend class này — không được dùng trực tiếp.
 *
 * <p>Chứa đầy đủ thông tin để tracking lifecycle từ lúc tạo đến khi kết thúc.
 * Framework dùng class này để điều phối Saga, validate idempotency, và Reconciliation.
 *
 * <p><b>Developer tự thêm:</b> các field nghiệp vụ riêng (vd: totalAmount,
 * seatNumbers, guestName...). Class con nên thêm {@code @Entity} nếu dùng JPA.
 *
 * <pre>{@code
 * @Entity
 * @Table(name = "concert_orders")
 * public class ConcertOrder extends AbstractOrder {
 *     private String seatNumbers;
 *     private BigDecimal totalAmount;
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
public abstract class AbstractOrder {

    /** Định danh duy nhất của order (UUID). */
    private String orderId;

    /** ID tài nguyên được đặt — liên kết với AbstractResource.resourceId. */
    private String resourceId;

    /** ID người đặt (userId, customerId...). */
    private String requesterId;

    /** Số lượng đặt trong order này. */
    private int quantity;

    /** Trạng thái hiện tại của order. */
    private OrderStatus status;

    /**
     * Key chống duplicate request.
     * Framework dùng field này để detect và reject request trùng lặp.
     * Developer cần đảm bảo client gửi cùng key cho cùng 1 ý định đặt hàng.
     */
    private String idempotencyKey;

    /**
     * Lý do thất bại chuẩn hóa (nếu có).
     * Được set khi status chuyển sang CANCELLED hoặc EXPIRED.
     */
    private FailureReason failureReason;

    /** Thời điểm tạo order. */
    private Instant createdAt;

    /** Thời điểm cập nhật gần nhất. */
    private Instant updatedAt;

    /**
     * Thời điểm hết hạn giữ chỗ.
     * Sau thời điểm này, Reconciliation sẽ tự cancel order và release inventory.
     * Mặc định: createdAt + hcr.saga.reservation-timeout-minutes (default 5 phút).
     */
    private Instant expiresAt;

    protected AbstractOrder(String orderId, String resourceId, String requesterId,
                            int quantity, String idempotencyKey) {
        this.orderId = orderId;
        this.resourceId = resourceId;
        this.requesterId = requesterId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // -------------------------------------------------------------------------
    // Query methods
    // -------------------------------------------------------------------------

    /** @return true nếu order đang ở PENDING. */
    public boolean isPending() {
        return this.status == OrderStatus.PENDING;
    }

    /** @return true nếu expiresAt đã qua thời điểm hiện tại. */
    public boolean isExpired() {
        return this.expiresAt != null && Instant.now().isAfter(this.expiresAt);
    }

    /**
     * @return true nếu order đã ở trạng thái kết thúc (CONFIRMED, CANCELLED, EXPIRED).
     * Terminal state không thể thay đổi sang trạng thái khác.
     */
    public boolean isTerminal() {
        return this.status != null && this.status.isTerminal();
    }

    // -------------------------------------------------------------------------
    // Internal state transitions — chỉ AbstractSagaOrchestrator gọi
    // -------------------------------------------------------------------------

    void transitionTo(OrderStatus next) {
        if (this.status != null && !this.status.canTransitionTo(next)) {
            throw new IllegalStateException(String.format(
                "Order %s: invalid transition %s -> %s", orderId, this.status, next));
        }
        this.status = next;
        this.updatedAt = Instant.now();
    }

    void markFailedWith(FailureReason reason) {
        this.failureReason = reason;
        this.updatedAt = Instant.now();
    }
}
