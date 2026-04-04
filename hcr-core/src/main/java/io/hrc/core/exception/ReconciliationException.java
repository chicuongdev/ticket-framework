package io.hrc.core.exception;

import io.hrc.core.enums.FailureReason;

/**
 * Ném khi Reconciliation gặp lỗi nghiêm trọng không thể tự xử lý.
 * Thường xảy ra khi:
 * <ul>
 *   <li>Delta inventory quá lớn — vượt ngưỡng cho phép auto-fix.</li>
 *   <li>Không thể kết nối DB hoặc Redis trong lúc reconcile.</li>
 *   <li>Phát hiện duplicate order không thể tự resolve.</li>
 * </ul>
 *
 * <p>Exception này trigger alert và cần sự can thiệp thủ công.
 */
public class ReconciliationException extends FrameworkException {

    public ReconciliationException(String resourceId, String message) {
        super(FailureReason.SYSTEM_ERROR, resourceId, null, message);
    }

    public ReconciliationException(String resourceId, String message, Throwable cause) {
        super(FailureReason.SYSTEM_ERROR, resourceId, null, message, cause);
    }
}
