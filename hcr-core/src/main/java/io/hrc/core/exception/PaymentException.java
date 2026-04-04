package io.hrc.core.exception;

import io.hrc.core.enums.FailureReason;

/**
 * Ném khi payment gateway gặp lỗi không thể recover.
 * Được phân biệt theo reason:
 * <ul>
 *   <li>{@link FailureReason#PAYMENT_FAILED} — gateway từ chối giao dịch.</li>
 *   <li>{@link FailureReason#PAYMENT_TIMEOUT} — gateway không phản hồi đúng hạn.</li>
 *   <li>{@link FailureReason#PAYMENT_UNKNOWN} — không rõ kết quả, cần Reconciliation xử lý.</li>
 * </ul>
 */
public class PaymentException extends FrameworkException {

    public PaymentException(FailureReason reason, String orderId, String message) {
        super(reason, null, orderId, message);
    }

    public PaymentException(FailureReason reason, String orderId, String message, Throwable cause) {
        super(reason, null, orderId, message, cause);
    }
}
