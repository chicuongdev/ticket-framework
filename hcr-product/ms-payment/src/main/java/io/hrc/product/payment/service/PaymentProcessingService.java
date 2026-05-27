package io.hrc.product.payment.service;

import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.payment.model.PaymentResult;
import io.hrc.payment.model.RefundRequest;
import io.hrc.payment.model.RefundResult;
import io.hrc.product.payment.domain.PaymentAttempt;
import io.hrc.product.payment.domain.PaymentAttemptStatus;
import io.hrc.product.payment.repository.PaymentAttemptRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Lõi xử lý thanh toán của ms-payment — service DUY NHẤT tích hợp cổng thanh toán.
 *
 * <p>Cổng vào duy nhất là HTTP {@code PaymentController POST /payments} — cả P1/P2 sync
 * lẫn P3 auto-charge async (ms-order's {@code AutoChargeInitiation} gọi qua RestClient)
 * đều đi qua đây.
 *
 * <p>Idempotency tự nhiên: {@code PaymentAttempt} PK = orderId. Gọi {@link #charge}
 * lại cho order đã resolved → trả luôn kết quả cũ, không charge cổng thanh toán lần nữa.
 */
@Service
@Slf4j
public class PaymentProcessingService {

    private final PaymentGateway paymentGateway;
    private final PaymentAttemptRepository repository;

    public PaymentProcessingService(PaymentGateway paymentGateway,
                                     PaymentAttemptRepository repository) {
        this.paymentGateway = paymentGateway;
        this.repository = repository;
    }

    /**
     * Charge một order qua cổng thanh toán. Idempotent theo orderId.
     *
     * @return kết quả thanh toán (SUCCESS / FAILED / TIMEOUT / UNKNOWN)
     */
    public PaymentResult charge(String orderId, String resourceId,
                                BigDecimal amount, String currency) {
        Optional<PaymentAttempt> existing = repository.findById(orderId);
        if (existing.isPresent() && existing.get().getStatus() != PaymentAttemptStatus.PENDING) {
            PaymentAttempt a = existing.get();
            log.info("[ms-payment] charge bỏ qua (đã xử lý): orderId={}, status={}",
                    orderId, a.getStatus());
            return toResult(a);
        }

        PaymentAttempt attempt = existing.orElseGet(() -> repository.save(
                new PaymentAttempt(orderId, resourceId, amount, currency)));

        PaymentRequest request = PaymentRequest.builder()
                .transactionId(orderId)
                .amount(toMinorUnits(amount, currency))
                .currency(currency)
                .description("Payment for order " + orderId)
                .build();

        PaymentResult result = paymentGateway.charge(request);
        persistOutcome(attempt, result);
        return result;
    }

    /**
     * Hỏi cổng thanh toán trạng thái thật của một giao dịch (dùng cho reconciliation).
     * Đồng bộ luôn {@code payment_attempts} nếu cổng trả kết quả dứt khoát.
     */
    public PaymentResult queryStatus(String orderId) {
        PaymentResult result = paymentGateway.queryStatus(orderId);
        if (result.isResolved()) {
            repository.findById(orderId).ifPresent(a -> {
                PaymentAttemptStatus resolved = result.isSuccess()
                        ? PaymentAttemptStatus.SUCCEEDED
                        : PaymentAttemptStatus.FAILED;
                if (a.getStatus() != resolved) {
                    a.markResolved(resolved, result.getGatewayTransactionId(),
                            result.getErrorCode(), result.getErrorMessage());
                    repository.save(a);
                    log.info("[ms-payment] payment_attempts đồng bộ orderId={} -> {}",
                            orderId, resolved);
                }
            });
        }
        return result;
    }

    /** Hoàn tiền cho một giao dịch (dùng cho saga compensation). */
    public RefundResult refund(String orderId, long amount, String reason) {
        RefundRequest request = RefundRequest.builder()
                .transactionId(orderId)
                .amount(amount)
                .reason(reason != null ? reason : "Order cancelled")
                .build();
        return paymentGateway.refund(request);
    }

    // ----- helpers -----

    private void persistOutcome(PaymentAttempt attempt, PaymentResult result) {
        PaymentAttemptStatus status;
        if (result.isSuccess()) status = PaymentAttemptStatus.SUCCEEDED;
        else if (result.isTimeout()) status = PaymentAttemptStatus.TIMEOUT;
        else if (result.isUnknown()) status = PaymentAttemptStatus.UNKNOWN;
        else status = PaymentAttemptStatus.FAILED;

        attempt.markResolved(status, result.getGatewayTransactionId(),
                result.getErrorCode(), result.getErrorMessage());
        repository.save(attempt);
    }

    /** Tái dựng PaymentResult từ PaymentAttempt đã lưu (cho idempotency replay). */
    private PaymentResult toResult(PaymentAttempt a) {
        return switch (a.getStatus()) {
            case SUCCEEDED -> PaymentResult.success(a.getOrderId(),
                    a.getGatewayTransactionId(),
                    toMinorUnits(a.getAmount(), a.getCurrency()));
            case FAILED -> PaymentResult.failed(a.getOrderId(), a.getErrorCode(), a.getErrorMessage());
            case TIMEOUT -> PaymentResult.timeout(a.getOrderId());
            default -> PaymentResult.unknown(a.getOrderId());
        };
    }

    /** PaymentRequest.amount kiểu long (đơn vị nhỏ nhất). VND/JPY không có cent. */
    private long toMinorUnits(BigDecimal amount, String currency) {
        if ("VND".equalsIgnoreCase(currency) || "JPY".equalsIgnoreCase(currency)) {
            return amount.longValueExact();
        }
        return amount.movePointRight(2).longValueExact();
    }
}
