package io.hrc.payment.model;

/**
 * Trạng thái health của payment gateway.
 *
 * <ul>
 *   <li>{@link #UP} — gateway hoạt động bình thường.</li>
 *   <li>{@link #DEGRADED} — gateway chậm hoặc success rate thấp, vẫn nhận request.</li>
 *   <li>{@link #DOWN} — gateway không phản hồi, nên dừng gửi request.</li>
 * </ul>
 */
public enum HealthStatus {

    UP,
    DEGRADED,
    DOWN;

    public boolean isHealthy() {
        return this == UP;
    }

    public boolean isDegraded() {
        return this == DEGRADED;
    }

    public boolean isDown() {
        return this == DOWN;
    }
}
