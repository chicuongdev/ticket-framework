package io.hrc.product.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Audit log mỗi lần ms-payment xử lý charge cho 1 order.
 *
 * <p>PK = orderId → idempotency tự nhiên: nếu đã có row cho orderId này thì
 * listener bỏ qua (event Kafka có thể deliver lại).
 */
@Entity
@Table(name = "payment_attempts")
@Getter
@Setter
@NoArgsConstructor
public class PaymentAttempt {

    @Id
    @Column(name = "order_id", length = 64)
    private String orderId;

    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentAttemptStatus status;

    @Column(name = "gateway_transaction_id", length = 128)
    private String gatewayTransactionId;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentAttempt(String orderId, String resourceId,
                          BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.resourceId = resourceId;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentAttemptStatus.PENDING;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void markResolved(PaymentAttemptStatus status,
                              String gatewayTxId,
                              String errorCode,
                              String errorMessage) {
        this.status = status;
        this.gatewayTransactionId = gatewayTxId;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.updatedAt = Instant.now();
    }
}
