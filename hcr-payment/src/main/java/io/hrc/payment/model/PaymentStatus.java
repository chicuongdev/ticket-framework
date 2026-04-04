package io.hrc.payment.model;

/**
 * Trạng thái kết quả thanh toán.
 *
 * <ul>
 *   <li>{@link #SUCCESS} — gateway xác nhận đã trừ tiền thành công.</li>
 *   <li>{@link #FAILED} — gateway từ chối giao dịch (thiếu tiền, thẻ hết hạn...).</li>
 *   <li>{@link #TIMEOUT} — gateway không phản hồi trong thời gian quy định
 *       → {@code TimeoutHandler} sẽ polling {@code queryStatus()}.</li>
 *   <li>{@link #UNKNOWN} — sau khi polling hết {@code maxPollingAttempts} vẫn không
 *       rõ kết quả → Reconciliation xử lý sau.</li>
 * </ul>
 */
public enum PaymentStatus {

    SUCCESS,
    FAILED,
    TIMEOUT,
    UNKNOWN;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isFailed() {
        return this == FAILED;
    }

    public boolean isTimeout() {
        return this == TIMEOUT;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    /**
     * Kết quả đã xác định rõ ràng (SUCCESS hoặc FAILED).
     * Dùng để check xem có cần polling/reconciliation tiếp không.
     */
    public boolean isResolved() {
        return this == SUCCESS || this == FAILED;
    }
}
