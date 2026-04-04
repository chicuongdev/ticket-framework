package io.hrc.eventbus.event.order;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

import java.time.Instant;

/**
 * Publish khi giữ chỗ hết hạn — ReconciliationService phát hiện order quá {@code expiresAt}.
 *
 * <p>Ai subscribe: {@code EmailService} (thông báo hết hạn),
 * {@code InventoryService} (release inventory bị giữ quá lâu).
 */
@Getter
public class OrderExpiredEvent extends DomainEvent {

    /** ID của người đặt hàng. */
    private final String requesterId;

    /** Số lượng inventory cần release. */
    private final int quantity;

    /** Thời điểm order đáng lẽ đã hết hạn. */
    private final Instant expiredAt;

    public OrderExpiredEvent(String resourceId, String orderId,
                              String requesterId, int quantity,
                              Instant expiredAt,
                              String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requesterId = requesterId;
        this.quantity    = quantity;
        this.expiredAt   = expiredAt;
    }
}
