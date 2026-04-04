package io.hrc.core.exception;

import io.hrc.core.enums.FailureReason;
import lombok.Getter;

/**
 * Base exception của HCR Framework.
 * Tất cả exception do framework ném ra đều extend từ class này.
 * Developer có thể catch một lần để xử lý tất cả lỗi framework:
 *
 * <pre>{@code
 * try {
 *     gateway.submit(request);
 * } catch (FrameworkException e) {
 *     log.error("HCR error: reason={}, resource={}, order={}",
 *         e.getReason(), e.getResourceId(), e.getOrderId());
 * }
 * }</pre>
 */
@Getter
public class FrameworkException extends RuntimeException {

    /** Lý do lỗi chuẩn hóa — dùng để phân loại và metrics. */
    private final FailureReason reason;

    /** ID tài nguyên liên quan (có thể null). */
    private final String resourceId;

    /** ID order liên quan (có thể null nếu lỗi xảy ra trước khi tạo order). */
    private final String orderId;

    public FrameworkException(FailureReason reason, String message) {
        super(message);
        this.reason = reason;
        this.resourceId = null;
        this.orderId = null;
    }

    public FrameworkException(FailureReason reason, String resourceId, String orderId, String message) {
        super(message);
        this.reason = reason;
        this.resourceId = resourceId;
        this.orderId = orderId;
    }

    public FrameworkException(FailureReason reason, String resourceId, String orderId,
                              String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.resourceId = resourceId;
        this.orderId = orderId;
    }
}
