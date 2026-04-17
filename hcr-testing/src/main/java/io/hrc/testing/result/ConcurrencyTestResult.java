package io.hrc.testing.result;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;

/**
 * Kết quả của {@code FrameworkTestSupport.simulateConcurrentRequests()}.
 *
 * <p>Rule bất biến: {@link #oversellCount} phải luôn = 0. Nếu &gt; 0 → framework có bug.
 */
@Getter
@Builder
public class ConcurrencyTestResult {

    private final int totalRequests;
    private final int successCount;
    private final int failureCount;

    /** Số lần oversell xảy ra — phải luôn bằng 0. */
    private final int oversellCount;

    private final long throughputTps;
    private final long p50LatencyMs;
    private final long p95LatencyMs;
    private final long p99LatencyMs;
    private final Duration totalDuration;
    private final List<String> errors;

    /** @return true nếu có oversell — luôn phải là false */
    public boolean hasOversell() {
        return oversellCount > 0;
    }

    /** @return tỉ lệ thành công [0.0, 1.0] */
    public double getSuccessRate() {
        if (totalRequests == 0) return 0.0;
        return (double) successCount / totalRequests;
    }

    /** @return true nếu không có lỗi nào */
    public boolean isClean() {
        return !hasOversell() && (errors == null || errors.isEmpty());
    }
}
