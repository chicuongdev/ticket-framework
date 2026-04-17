package io.hrc.reconciliation.model;

import io.hrc.payment.model.PaymentResult;
import io.hrc.payment.model.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

/**
 * Kết quả verify một order với payment gateway.
 *
 * <p>Được tạo bởi {@link io.hrc.reconciliation.order.OrderReconciler#verify(io.hrc.core.domain.AbstractOrder, String)}.
 * Dùng trong xử lý Case 1 (STALE_PENDING) và Case 2 (LATE_PAYMENT_SUCCESS).
 *
 * <p>Flow:
 * <pre>
 *   paymentGateway.queryStatus(transactionId)
 *       → SUCCESS  → Case 2 (LATE_PAYMENT_SUCCESS): tiền đã trừ, confirm order
 *       → FAILED   → Case 1 (STALE_PENDING): gateway xử lý xong nhưng fail, cancel
 *       → UNKNOWN  → Case 1 (STALE_PENDING): không rõ, cancel + log để manual review
 *       → TIMEOUT  → Case 1 (STALE_PENDING): gateway không phản hồi, cancel + alert
 * </pre>
 */
@Getter
@Builder
public class PaymentVerificationResult {

    /** ID order được verify. */
    private final String orderId;

    /** Transaction ID đã dùng để query gateway. */
    private final String transactionId;

    /**
     * Kết quả từ {@code paymentGateway.queryStatus()}.
     * Null nếu query thất bại (gateway không phản hồi / exception).
     */
    private final PaymentResult paymentResult;

    /**
     * {@code true} nếu query gateway thành công (có kết quả, dù SUCCESS/FAILED/UNKNOWN).
     * {@code false} nếu gateway exception hoặc không phản hồi.
     */
    private final boolean verified;

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    /**
     * @return {@code true} nếu gateway xác nhận payment thành công.
     *         Đây là Case 2 — cần reconfirm order hoặc refund.
     */
    public boolean isPaymentSuccess() {
        return verified && paymentResult != null
                && paymentResult.getStatus() == PaymentStatus.SUCCESS;
    }

    /**
     * @return {@code true} nếu gateway xác nhận payment thất bại.
     *         Order nên bị cancel và inventory được release.
     */
    public boolean isPaymentFailed() {
        return verified && paymentResult != null
                && paymentResult.getStatus() == PaymentStatus.FAILED;
    }

    /**
     * @return {@code true} nếu không thể xác định kết quả payment.
     *         Bao gồm: UNKNOWN, TIMEOUT, hoặc gateway không phản hồi ({@code !verified}).
     *         → Cancel order + alert để manual review.
     */
    public boolean isPaymentUnresolvable() {
        if (!verified || paymentResult == null) return true;
        PaymentStatus s = paymentResult.getStatus();
        return s == PaymentStatus.UNKNOWN || s == PaymentStatus.TIMEOUT;
    }
}
