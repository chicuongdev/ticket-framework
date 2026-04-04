package io.hrc.eventbus.event.payment;

import io.hrc.core.domain.DomainEvent;
import io.hrc.core.enums.FailureReason;
import lombok.Getter;

import java.time.Duration;

/**
 * Publish khi payment gateway không phản hồi trong thời gian quy định.
 *
 * <p>Kết quả thanh toán chưa biết — có thể đã charge hoặc chưa.
 * ReconciliationService cần query lại gateway sau để xác định.
 *
 * <p>Ai subscribe: {@code ReconciliationService} — để schedule re-check payment.
 */
@Getter
public class PaymentTimeoutEvent extends DomainEvent {

    /** Lý do — luôn là {@code PAYMENT_TIMEOUT}. */
    private final FailureReason reason;

    /** Thời gian đã chờ trước khi timeout. */
    private final Duration waitedFor;

    public PaymentTimeoutEvent(String resourceId, String orderId,
                                Duration waitedFor,
                                String correlationId) {
        super(resourceId, orderId, correlationId);
        this.reason     = FailureReason.PAYMENT_TIMEOUT;
        this.waitedFor  = waitedFor;
    }
}
