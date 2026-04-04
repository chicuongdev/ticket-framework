package io.hrc.inventory.decorator;

import io.hrc.core.enums.ConsistencyLevel;
import io.hrc.core.result.InventorySnapshot;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.inventory.strategy.InventoryStrategy;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Decorator bọc bất kỳ {@link InventoryStrategy} nào, thêm Circuit Breaker behavior.
 * Không thay đổi logic của strategy gốc — chỉ thêm lớp bảo vệ bên ngoài.
 *
 * <p><b>Mục đích:</b> Khi Redis hoặc DB bị degraded, CB mở ra để tránh cascade failure
 * — thay vì tất cả request chờ timeout, CB reject ngay lập tức và trả lỗi fast.
 *
 * <pre>
 * CLOSED ──(error rate > threshold)──► OPEN
 *   ▲                                    │
 *   │                           (after waitDuration)
 *   │                                    ▼
 * CLOSED ◄──(probe success)──── HALF_OPEN
 *                                    │
 *                           (probe failure)
 *                                    ▼
 *                                  OPEN
 * </pre>
 */
@Slf4j
public class CircuitBreakerInventoryDecorator implements InventoryStrategy {

    private final InventoryStrategy delegate;
    private final int slidingWindowSize;
    private final double failureRateThreshold;    // 0.0 - 1.0
    private final long waitDurationMs;

    /** Trạng thái hiện tại của circuit breaker. */
    private final AtomicReference<CircuitBreakerState> state =
        new AtomicReference<>(CircuitBreakerState.CLOSED);

    /** Thời điểm CB chuyển sang OPEN — dùng để tính khi nào sang HALF_OPEN. */
    private volatile Instant openedAt;

    /** Sliding window của các kết quả gần nhất: true=success, false=failure. */
    private final Deque<Boolean> slidingWindow = new ArrayDeque<>();

    /** Số lần failure trong sliding window. */
    private final AtomicInteger failureCount = new AtomicInteger(0);

    public CircuitBreakerInventoryDecorator(InventoryStrategy delegate,
                                             int slidingWindowSize,
                                             double failureRateThreshold,
                                             long waitDurationMs) {
        this.delegate = delegate;
        this.slidingWindowSize = slidingWindowSize;
        this.failureRateThreshold = failureRateThreshold;
        this.waitDurationMs = waitDurationMs;
    }

    // =========================================================================
    // Core Operations — decorated với CB logic
    // =========================================================================

    @Override
    public ReservationResult reserve(String resourceId, String requestId, int quantity) {
        checkAndTransitionState();

        CircuitBreakerState current = state.get();

        if (current == CircuitBreakerState.OPEN) {
            log.warn("[CB] Circuit OPEN — reject reserve: resourceId={}", resourceId);
            return ReservationResult.error(resourceId, quantity,
                "Circuit breaker OPEN — inventory service degraded, retry later");
        }

        try {
            ReservationResult result = delegate.reserve(resourceId, requestId, quantity);
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    @Override
    public void release(String resourceId, String requestId, int quantity) {
        checkAndTransitionState();

        if (state.get() == CircuitBreakerState.OPEN) {
            log.error("[CB] Circuit OPEN — cannot release: resourceId={}. " +
                      "Reconciliation will fix this.", resourceId);
            // Release không reject — để Reconciliation xử lý sau
            return;
        }

        try {
            delegate.release(resourceId, requestId, quantity);
            recordSuccess();
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    // =========================================================================
    // Query & Management — delegate trực tiếp, không cần CB wrap
    // =========================================================================

    @Override
    public long getAvailable(String resourceId) {
        return delegate.getAvailable(resourceId);
    }

    @Override
    public boolean isAvailable(String resourceId) {
        return delegate.isAvailable(resourceId);
    }

    @Override
    public boolean isAvailable(String resourceId, int quantity) {
        return delegate.isAvailable(resourceId, quantity);
    }

    @Override
    public InventorySnapshot getSnapshot(String resourceId) {
        return delegate.getSnapshot(resourceId);
    }

    @Override
    public void initialize(String resourceId, long totalQuantity) {
        delegate.initialize(resourceId, totalQuantity);
    }

    @Override
    public void restock(String resourceId, long quantity) {
        delegate.restock(resourceId, quantity);
    }

    @Override
    public void deactivate(String resourceId) {
        delegate.deactivate(resourceId);
    }

    @Override
    public Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests) {
        checkAndTransitionState();
        if (state.get() == CircuitBreakerState.OPEN) {
            Map<String, ReservationResult> results = new java.util.HashMap<>();
            requests.forEach((resourceId, qty) ->
                results.put(resourceId, ReservationResult.error(resourceId, qty, "Circuit breaker OPEN")));
            return results;
        }
        try {
            Map<String, ReservationResult> result = delegate.reserveBatch(requests);
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    @Override
    public void releaseBatch(Map<String, Integer> releases) {
        delegate.releaseBatch(releases);
    }

    @Override
    public boolean isLowStock(String resourceId, long threshold) {
        return delegate.isLowStock(resourceId, threshold);
    }

    @Override
    public InventoryMetrics getMetrics() {
        return delegate.getMetrics();
    }

    @Override
    public String getStrategyName() {
        return delegate.getStrategyName() + "+circuit-breaker";
    }

    @Override
    public ConsistencyLevel getConsistencyLevel() {
        return delegate.getConsistencyLevel();
    }

    // =========================================================================
    // CB state machine
    // =========================================================================

    /** OPEN → HALF_OPEN nếu đã hết waitDuration. */
    private void checkAndTransitionState() {
        if (state.get() == CircuitBreakerState.OPEN) {
            if (Instant.now().isAfter(openedAt.plusMillis(waitDurationMs))) {
                if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
                    log.info("[CB] OPEN → HALF_OPEN: probe request will be allowed");
                }
            }
        }
    }

    private synchronized void recordSuccess() {
        addToWindow(true);
        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            state.set(CircuitBreakerState.CLOSED);
            log.info("[CB] HALF_OPEN → CLOSED: probe success");
        }
    }

    private synchronized void recordFailure() {
        addToWindow(false);
        double currentRate = (double) failureCount.get() / slidingWindowSize;

        if (currentRate >= failureRateThreshold) {
            if (state.get() != CircuitBreakerState.OPEN) {
                state.set(CircuitBreakerState.OPEN);
                openedAt = Instant.now();
                log.warn("[CB] CLOSED/HALF_OPEN → OPEN: failureRate={:.1f}%",
                    currentRate * 100);
            }
        }
    }

    /** Thêm result vào sliding window — xóa phần tử cũ nhất nếu đầy. */
    private void addToWindow(boolean success) {
        if (slidingWindow.size() >= slidingWindowSize) {
            Boolean removed = slidingWindow.pollFirst();
            if (Boolean.FALSE.equals(removed)) {
                failureCount.decrementAndGet();
            }
        }
        slidingWindow.addLast(success);
        if (!success) {
            failureCount.incrementAndGet();
        }
    }

    /** Lấy trạng thái hiện tại — dùng cho monitoring và actuator endpoint. */
    public CircuitBreakerState getState() {
        return state.get();
    }
}
