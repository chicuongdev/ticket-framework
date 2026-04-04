package io.hrc.eventbus.event.payment;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Publish khi thanh toán thành công.
 *
 * <p>Ai subscribe: {@code ConfirmationConsumer} — để chuyển order sang CONFIRMED.
 */
@Getter
public class PaymentSucceededEvent extends DomainEvent {

    /** ID transaction từ payment gateway. */
    private final String transactionId;

    /** Số tiền đã thanh toán. */
    private final BigDecimal amount;

    /** Currency code (VND, USD...). */
    private final String currency;

    public PaymentSucceededEvent(String resourceId, String orderId,
                                  String transactionId,
                                  BigDecimal amount, String currency,
                                  String correlationId) {
        super(resourceId, orderId, correlationId);
        this.transactionId = transactionId;
        this.amount        = amount;
        this.currency      = currency;
    }
}
