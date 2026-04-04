package io.hrc.eventbus.event.order;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/**
 * Publish khi order vừa được tạo trong AsyncSagaOrchestrator.
 *
 * <p>Ai subscribe: {@code PaymentConsumer} — để bắt đầu xử lý thanh toán.
 *
 * <p>Chỉ dùng trong async Saga mode. Sync Saga xử lý payment trực tiếp,
 * không cần event này.
 */
@Getter
public class OrderCreatedEvent extends DomainEvent {

    /** ID của người đặt hàng. */
    private final String requesterId;

    /** Số lượng đã đặt. */
    private final int quantity;

    public OrderCreatedEvent(String resourceId, String orderId,
                              String requesterId, int quantity,
                              String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requesterId = requesterId;
        this.quantity    = quantity;
    }
}
