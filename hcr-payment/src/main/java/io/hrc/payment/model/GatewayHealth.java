package io.hrc.payment.model;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Trạng thái health của payment gateway tại thời điểm query.
 *
 * <p>Dùng để quyết định có nên gọi gateway không — tránh gửi request khi gateway đang DOWN.
 * {@link AbstractPaymentGateway} tự động check trước khi gọi {@code doCharge()}.
 *
 * <pre>{@code
 * GatewayHealth health = gateway.getHealth();
 * if (health.isDown()) {
 *     // circuit breaker mở → queue request hoặc trả lỗi ngay
 * }
 * }</pre>
 */
@Getter
@Builder
public class GatewayHealth {

    private final HealthStatus status;

    /** Tỉ lệ thành công trong 5 phút gần nhất (0.0 → 1.0). */
    private final double successRateLast5Min;

    /** Latency trung bình (ms) trong 5 phút gần nhất. */
    private final double avgLatencyMs;

    /** Số connection đang mở tới gateway. */
    private final int activeConnections;

    /** Thời điểm kiểm tra. */
    @Builder.Default
    private final Instant checkedAt = Instant.now();

    // -------------------------------------------------------------------------
    // Convenience methods — delegate to enum
    // -------------------------------------------------------------------------

    public boolean isHealthy() {
        return status.isHealthy();
    }

    public boolean isDegraded() {
        return status.isDegraded();
    }

    public boolean isDown() {
        return status.isDown();
    }

    // -------------------------------------------------------------------------
    // Static factories cho common cases
    // -------------------------------------------------------------------------

    public static GatewayHealth up(double successRate, double avgLatencyMs) {
        return GatewayHealth.builder()
                .status(HealthStatus.UP)
                .successRateLast5Min(successRate)
                .avgLatencyMs(avgLatencyMs)
                .build();
    }

    public static GatewayHealth down() {
        return GatewayHealth.builder()
                .status(HealthStatus.DOWN)
                .successRateLast5Min(0.0)
                .avgLatencyMs(0.0)
                .build();
    }
}
