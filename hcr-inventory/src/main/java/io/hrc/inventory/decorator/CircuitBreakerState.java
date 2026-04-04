package io.hrc.inventory.decorator;

/** Ba trạng thái của circuit breaker. */
public enum CircuitBreakerState {
    /** Bình thường — tất cả request đi qua strategy. */
    CLOSED,
    /** Đang lỗi — reject ngay lập tức, không gọi strategy. */
    OPEN,
    /** Đang kiểm tra — cho phép một probe request thử lại. */
    HALF_OPEN
}
