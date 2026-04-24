package io.hrc.product.shared.event;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * ms-order publish khi cần ms-payment thực hiện charge.
 *
 * <p>Khác với framework's {@code OrderCreatedEvent} (chỉ mang quantity), event này
 * mang thêm amount + currency vì ms-payment không có context về bảng giá vé.
 *
 * <p>Routing: topic {@code hcr.payment.commands} (cấu hình qua EventDestination).
 */
@Getter
@NoArgsConstructor
public class PaymentRequestedEvent extends DomainEvent {

    /** ID người đặt — propagate cho gateway nhận diện. */
    private String requesterId;

    /** Số tiền cần charge. */
    private BigDecimal amount;

    /** Currency code (VND, USD...). */
    private String currency;

    public PaymentRequestedEvent(String resourceId, String orderId,
                                  String requesterId, BigDecimal amount,
                                  String currency, String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requesterId = requesterId;
        this.amount = amount;
        this.currency = currency;
    }
}
