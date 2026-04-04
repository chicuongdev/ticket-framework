package io.hrc.core.enums;

/**
 * Chuẩn hóa toàn bộ lifecycle của một order.
 * Framework quy định chỉ có đúng 6 trạng thái — không thêm, không bớt.
 *
 * <pre>
 * PENDING ──► RESERVED ──► CONFIRMED  (terminal)
 *    │            │
 *    │            └──► COMPENSATING ──► CANCELLED (terminal)
 *    │            │
 *    └────────────└──► CANCELLED (terminal)
 *                 └──► EXPIRED   (terminal)
 * </pre>
 */
public enum OrderStatus {

    /** Vừa tạo, chưa được xử lý. */
    PENDING,

    /** Đã giữ chỗ trong inventory, đang chờ thanh toán. */
    RESERVED,

    /** Thanh toán thành công — terminal state, không thể thay đổi. */
    CONFIRMED,

    /** Đã hủy (do lỗi, hết hàng, hoặc user cancel) — terminal state. */
    CANCELLED,

    /** Giữ chỗ hết hạn trước khi thanh toán — terminal state. */
    EXPIRED,

    /** Đang trong quá trình rollback (compensating transaction). Kết thúc bằng CANCELLED. */
    COMPENSATING;

    /**
     * Kiểm tra trạng thái này có phải terminal không.
     * Terminal state không thể chuyển sang trạng thái khác.
     */
    public boolean isTerminal() {
        return this == CONFIRMED || this == CANCELLED || this == EXPIRED;
    }

    /**
     * Kiểm tra chuyển trạng thái có hợp lệ không.
     * Framework gọi method này trước khi update DB để bảo vệ tính nhất quán.
     */
    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING      -> next == RESERVED || next == CANCELLED;
            case RESERVED     -> next == CONFIRMED || next == CANCELLED
                                 || next == EXPIRED || next == COMPENSATING;
            case COMPENSATING -> next == CANCELLED;
            case CONFIRMED, CANCELLED, EXPIRED -> false; // terminal — không đi đâu nữa
        };
    }
}
