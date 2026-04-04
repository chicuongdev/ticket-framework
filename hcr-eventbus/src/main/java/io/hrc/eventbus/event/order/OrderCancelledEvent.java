package io.hrc.eventbus.event.order;

import io.hrc.core.domain.DomainEvent;
import io.hrc.core.enums.FailureReason;
import lombok.Getter;

/**
 * Publish khi order bị hủy — do thanh toán thất bại hoặc cancel thủ công.
 *
 * <p>Ai subscribe: {@code EmailService} (gửi cancellation email),
 * {@code InventoryService} (release inventory nếu chưa release).
 */
@Getter
public class OrderCancelledEvent extends DomainEvent {

    /** ID của người đặt hàng. */
    private final String requesterId;

    /** Lý do hủy. */
    private final FailureReason reason;

    /** Số lượng cần release (để consumer biết cần release bao nhiêu). */
    private final int quantity;

    public OrderCancelledEvent(String resourceId, String orderId,
                                String requesterId, int quantity,
                                FailureReason reason,
                                String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requesterId = requesterId;
        this.quantity    = quantity;
        this.reason      = reason;
    }
}
