package io.hrc.payment.model;

import lombok.Getter;

import java.time.Instant;

/**
 * Kết quả trả về từ payment gateway.
 *
 * <p>Thiết kế theo <b>Result Object pattern</b> — tương tự {@code ReservationResult}
 * trong hcr-core. Không throw exception cho business case, chỉ exception cho lỗi hệ thống.
 *
 * <p>Dùng factory methods:
 * <pre>{@code
 * // Gateway trả về thành công
 * PaymentResult.success("tx-001", "gw-abc-123", 150_000L);
 *
 * // Gateway từ chối
 * PaymentResult.failed("tx-001", "INSUFFICIENT_FUNDS", "Số dư không đủ");
 *
 * // Gateway không phản hồi → TimeoutHandler polling
 * PaymentResult.timeout("tx-001");
 *
 * // Polling xong vẫn không rõ → Reconciliation xử lý
 * PaymentResult.unknown("tx-001");
 * }</pre>
 */
@Getter
public class PaymentResult {

    private final PaymentStatus status;

    /** ID giao dịch phía chúng ta (= orderId). */
    private final String transactionId;

    /** ID giao dịch phía gateway — dùng để query/refund sau này. */
    private final String gatewayTransactionId;

    /** Số tiền thực tế đã xử lý (có thể khác request nếu gateway adjust). */
    private final long amount;

    /** Thời điểm gateway xử lý xong. Null nếu chưa xử lý xong. */
    private final Instant processedAt;

    /** Mã lỗi từ gateway (nếu FAILED). Null nếu SUCCESS. */
    private final String errorCode;

    /** Mô tả lỗi chi tiết (nếu FAILED). Null nếu SUCCESS. */
    private final String errorMessage;

    private PaymentResult(PaymentStatus status, String transactionId,
                          String gatewayTransactionId, long amount,
                          Instant processedAt, String errorCode, String errorMessage) {
        this.status = status;
        this.transactionId = transactionId;
        this.gatewayTransactionId = gatewayTransactionId;
        this.amount = amount;
        this.processedAt = processedAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static PaymentResult success(String transactionId, String gatewayTransactionId, long amount) {
        return new PaymentResult(
                PaymentStatus.SUCCESS, transactionId, gatewayTransactionId,
                amount, Instant.now(), null, null
        );
    }

    public static PaymentResult failed(String transactionId, String errorCode, String errorMessage) {
        return new PaymentResult(
                PaymentStatus.FAILED, transactionId, null,
                0, Instant.now(), errorCode, errorMessage
        );
    }

    public static PaymentResult timeout(String transactionId) {
        return new PaymentResult(
                PaymentStatus.TIMEOUT, transactionId, null,
                0, null, null, null
        );
    }

    public static PaymentResult unknown(String transactionId) {
        return new PaymentResult(
                PaymentStatus.UNKNOWN, transactionId, null,
                0, null, null, null
        );
    }

    // -------------------------------------------------------------------------
    // Convenience methods — delegate to enum
    // -------------------------------------------------------------------------

    public boolean isSuccess() {
        return status.isSuccess();
    }

    public boolean isFailed() {
        return status.isFailed();
    }

    public boolean isTimeout() {
        return status.isTimeout();
    }

    public boolean isUnknown() {
        return status.isUnknown();
    }

    /** Kết quả đã xác định? (SUCCESS hoặc FAILED) */
    public boolean isResolved() {
        return status.isResolved();
    }
}
