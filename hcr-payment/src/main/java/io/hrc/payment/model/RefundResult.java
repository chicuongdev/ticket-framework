package io.hrc.payment.model;

import lombok.Getter;

import java.time.Instant;

/**
 * Kết quả trả về từ thao tác refund.
 *
 * <p>Result Object pattern — tương tự {@link PaymentResult}.
 *
 * <pre>{@code
 * RefundResult result = gateway.refund(refundRequest);
 * if (result.isSuccess()) {
 *     // refund OK → confirm cancel
 * }
 * }</pre>
 */
@Getter
public class RefundResult {

    public enum Status {
        SUCCESS,
        FAILED,
        PENDING,
        UNKNOWN
    }

    private final Status status;

    /** ID refund phía gateway. */
    private final String refundId;

    /** ID giao dịch gốc. */
    private final String originalTransactionId;

    /** Số tiền thực tế đã refund. */
    private final long refundedAmount;

    private final Instant processedAt;
    private final String errorCode;
    private final String errorMessage;

    private RefundResult(Status status, String refundId, String originalTransactionId,
                         long refundedAmount, Instant processedAt,
                         String errorCode, String errorMessage) {
        this.status = status;
        this.refundId = refundId;
        this.originalTransactionId = originalTransactionId;
        this.refundedAmount = refundedAmount;
        this.processedAt = processedAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static RefundResult success(String refundId, String originalTransactionId, long refundedAmount) {
        return new RefundResult(
                Status.SUCCESS, refundId, originalTransactionId,
                refundedAmount, Instant.now(), null, null
        );
    }

    public static RefundResult failed(String originalTransactionId, String errorCode, String errorMessage) {
        return new RefundResult(
                Status.FAILED, null, originalTransactionId,
                0, Instant.now(), errorCode, errorMessage
        );
    }

    public static RefundResult pending(String refundId, String originalTransactionId) {
        return new RefundResult(
                Status.PENDING, refundId, originalTransactionId,
                0, null, null, null
        );
    }

    public static RefundResult unknown(String originalTransactionId) {
        return new RefundResult(
                Status.UNKNOWN, null, originalTransactionId,
                0, null, null, null
        );
    }

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }

    public boolean isFailed() {
        return status == Status.FAILED;
    }

    public boolean isPending() {
        return status == Status.PENDING;
    }

    public boolean isUnknown() {
        return status == Status.UNKNOWN;
    }
}
