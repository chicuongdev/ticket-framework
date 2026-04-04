package io.hrc.core.enums;

/**
 * Chuẩn hóa lý do thất bại trong toàn bộ framework.
 * Được dùng trong: AbstractOrder.failureReason, ReservationResult,
 * FrameworkException, DomainEvent, và metrics.
 */
public enum FailureReason {

    /** Không đủ tài nguyên để đặt — xảy ra ở Inventory module. */
    INSUFFICIENT_INVENTORY,

    /** Thanh toán thất bại (gateway từ chối) — xảy ra ở Payment module. */
    PAYMENT_FAILED,

    /** Gateway không phản hồi trong thời gian quy định — xảy ra ở Payment module. */
    PAYMENT_TIMEOUT,

    /** Không rõ kết quả thanh toán — trigger Reconciliation xử lý. */
    PAYMENT_UNKNOWN,

    /** idempotencyKey đã được xử lý trước đó — xảy ra ở Gateway module. */
    DUPLICATE_REQUEST,

    /** Input không hợp lệ — xảy ra ở Gateway module. */
    VALIDATION_FAILED,

    /** Giữ chỗ hết hạn trước khi thanh toán — xảy ra ở Reconciliation. */
    RESERVATION_EXPIRED,

    /** Lỗi hệ thống không xác định — có thể xảy ra bất kỳ đâu. */
    SYSTEM_ERROR
}
