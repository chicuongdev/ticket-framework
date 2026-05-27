package io.hrc.reconciliation;

/**
 * Phân loại 5 loại inconsistency mà framework phát hiện và xử lý định kỳ.
 *
 * <p>Mỗi case tương ứng với một kịch bản crash/failure trong distributed system:
 *
 * <pre>
 * Case 1 — STALE_PENDING:
 *   Order ở PENDING quá lâu. Payment gateway lỗi / consumer crash.
 *   → queryStatus() để xác minh, sau đó cancel hoặc confirm.
 *
 * Case 2 — LATE_PAYMENT_SUCCESS:
 *   Order bị CANCELLED (do timeout) nhưng thực ra tiền đã bị trừ.
 *   Đây là case NGUY HIỂM NHẤT — khách mất tiền.
 *   → reconfirm nếu còn inventory, hoặc refund ngay + alert.
 *
 * Case 3 — INVENTORY_MISMATCH:
 *   Redis available ≠ DB available (P3 only).
 *   Redis crash + AOF restore không đầy đủ → Redis có nhiều hơn thực tế.
 *   → tự fix Redis theo DB nếu delta nhỏ (trong ngưỡng cho phép).
 *
 * Case 4 — UNPERSISTED_RESERVATION:
 *   Order CONFIRMED nhưng DB chưa được cập nhật inventory.
 *   Consumer lag cao hoặc consumer crash sau ACK nhưng trước flush.
 *   → replay event hoặc force update DB theo Redis state.
 *
 * Case 5 — DUPLICATE_ORDER:
 *   Tồn tại 2+ order cùng idempotencyKey — idempotency layer bị bypass.
 *   → giữ 1 order (ưu tiên CONFIRMED), cancel các order còn lại.
 *
 * Case 6 — ORPHAN_CANCELLED:
 *   Order ở CANCELLED/EXPIRED nhưng inventory_released_at IS NULL —
 *   saga compensate gọi release() fail (retry hết) → orphan.
 *   → retry release(), set inventory_released_at khi thành công.
 * </pre>
 *
 * @see AbstractReconciliationService
 */
public enum ReconciliationCase {

    /** Order ở trạng thái PENDING quá lâu (vượt configurable timeout). */
    STALE_PENDING,

    /** Tiền đã bị trừ thành công nhưng order bị cancel do timeout trước đó. */
    LATE_PAYMENT_SUCCESS,

    /** Giá trị available trong Redis khác với DB (chỉ xảy ra với P3). */
    INVENTORY_MISMATCH,

    /** Order đã CONFIRMED nhưng DB inventory chưa được cập nhật (consumer lag/crash). */
    UNPERSISTED_RESERVATION,

    /** Tồn tại 2+ order với cùng idempotencyKey — idempotency bị bypass. */
    DUPLICATE_ORDER,

    /**
     * Order CANCELLED/EXPIRED nhưng inventory chưa được release (saga compensate fail retry hết).
     * Reconciliation retry release để đảm bảo zero leak (eventually consistent).
     */
    ORPHAN_CANCELLED
}
