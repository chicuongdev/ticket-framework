package io.hrc.eventbus.event.payment;

import io.hrc.core.domain.DomainEvent;
import io.hrc.core.enums.FailureReason;
import lombok.Getter;

/**
 * Publish khi kết quả thanh toán không rõ ràng — gateway trả response không xác định.
 *
 * <p>Nghiêm trọng hơn {@link PaymentTimeoutEvent}: gateway đã phản hồi nhưng
 * response không parse được hoặc trạng thái không nằm trong expected states.
 *
 * <p>Ai subscribe: {@code ReconciliationService} — để schedule manual verification.
 */
@Getter
public class PaymentUnknownEvent extends DomainEvent {

    /** Lý do — luôn là {@code PAYMENT_UNKNOWN}. */
    private final FailureReason reason;

    /** Raw response từ gateway để debug. */
    private final String rawGatewayResponse;

    public PaymentUnknownEvent(String resourceId, String orderId,
                                String rawGatewayResponse,
                                String correlationId) {
        super(resourceId, orderId, correlationId);
        this.reason              = FailureReason.PAYMENT_UNKNOWN;
        this.rawGatewayResponse  = rawGatewayResponse;
    }
}
