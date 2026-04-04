package io.hrc.eventbus.event.payment;

import io.hrc.core.domain.DomainEvent;
import io.hrc.core.enums.FailureReason;
import lombok.Getter;

/**
 * Publish khi thanh toán thất bại với lý do rõ ràng (declined, insufficient funds...).
 *
 * <p>Ai subscribe: {@code CancellationConsumer} — để hủy order và release inventory.
 */
@Getter
public class PaymentFailedEvent extends DomainEvent {

    /** Lý do thất bại — luôn là {@code PAYMENT_FAILED}. */
    private final FailureReason reason;

    /** Message từ payment gateway (để log và debug). */
    private final String gatewayMessage;

    public PaymentFailedEvent(String resourceId, String orderId,
                               String gatewayMessage,
                               String correlationId) {
        super(resourceId, orderId, correlationId);
        this.reason         = FailureReason.PAYMENT_FAILED;
        this.gatewayMessage = gatewayMessage;
    }
}
