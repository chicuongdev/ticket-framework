package io.hrc.eventbus.event.order;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/**
 * Publish khi order hoàn thành — thanh toán thành công, inventory đã được giữ.
 *
 * <p>Ai subscribe: {@code EmailService} (gửi confirmation email),
 * {@code AnalyticsService} (track conversion).
 */
@Getter
public class OrderConfirmedEvent extends DomainEvent {

    /** ID của người đặt hàng. */
    private final String requesterId;

    /** Số lượng đã xác nhận. */
    private final int quantity;

    public OrderConfirmedEvent(String resourceId, String orderId,
                                String requesterId, int quantity,
                                String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requesterId = requesterId;
        this.quantity    = quantity;
    }
}
