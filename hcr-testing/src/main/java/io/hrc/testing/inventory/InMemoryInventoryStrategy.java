package io.hrc.testing.inventory;

import io.hrc.core.enums.ConsistencyLevel;
import io.hrc.core.result.InventorySnapshot;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.inventory.strategy.InventoryStrategy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe in-memory inventory — dùng cho unit test và benchmark.
 * Không cần Redis hay DB. Zero-oversell đảm bảo qua AtomicLong CAS.
 *
 * <p>Testing utilities: {@link #getCurrentAvailable}, {@link #getReserveCallCount},
 * {@link #getOversellAttemptCount}, {@link #reset}.
 */
public class InMemoryInventoryStrategy implements InventoryStrategy {

    private final ConcurrentHashMap<String, AtomicLong> available = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> total = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> deactivated = new ConcurrentHashMap<>();

    // Bộ đếm kiểm thử
    private final ConcurrentHashMap<String, AtomicLong> reserveCallCount = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> oversellAttemptCount = new ConcurrentHashMap<>();

    @Override
    public ReservationResult reserve(String resourceId, String requestId, int quantity) {
        reserveCallCount.computeIfAbsent(resourceId, k -> new AtomicLong()).incrementAndGet();

        if (Boolean.TRUE.equals(deactivated.get(resourceId))) {
            return ReservationResult.error(resourceId, quantity, "Tài nguyên đã bị vô hiệu hóa");
        }

        AtomicLong avail = available.get(resourceId);
        if (avail == null) {
            return ReservationResult.error(resourceId, quantity, "Tài nguyên chưa được khởi tạo");
        }

        while (true) {
            long current = avail.get();
            if (current < quantity) {
                oversellAttemptCount.computeIfAbsent(resourceId, k -> new AtomicLong()).incrementAndGet();
                return ReservationResult.insufficient(resourceId, quantity);
            }
            if (avail.compareAndSet(current, current - quantity)) {
                return ReservationResult.success(resourceId, quantity, current - quantity);
            }
        }
    }

    @Override
    public void release(String resourceId, String requestId, int quantity) {
        AtomicLong avail = available.get(resourceId);
        if (avail == null) return;

        AtomicLong tot = total.get(resourceId);
        long cap = (tot != null) ? tot.get() : Long.MAX_VALUE;

        while (true) {
            long current = avail.get();
            long next = Math.min(current + quantity, cap);
            if (avail.compareAndSet(current, next)) return;
        }
    }

    @Override
    public long getAvailable(String resourceId) {
        AtomicLong avail = available.get(resourceId);
        return avail == null ? 0L : avail.get();
    }

    @Override
    public boolean isAvailable(String resourceId) {
        return getAvailable(resourceId) > 0;
    }

    @Override
    public boolean isAvailable(String resourceId, int quantity) {
        return getAvailable(resourceId) >= quantity;
    }

    @Override
    public InventorySnapshot getSnapshot(String resourceId) {
        long avail = getAvailable(resourceId);
        AtomicLong tot = total.get(resourceId);
        long totalQty = (tot != null) ? tot.get() : 0L;
        return InventorySnapshot.builder()
                .resourceId(resourceId)
                .totalQuantity(totalQty)
                .availableQuantity(avail)
                .reservedQuantity(0L)
                .confirmedQuantity(totalQty - avail)
                .snapshotAt(Instant.now())
                .source("in-memory")
                .build();
    }

    @Override
    public void initialize(String resourceId, long totalQuantity) {
        if (available.containsKey(resourceId)) {
            throw new IllegalStateException("Tài nguyên đã được khởi tạo trước đó: " + resourceId);
        }
        available.put(resourceId, new AtomicLong(totalQuantity));
        total.put(resourceId, new AtomicLong(totalQuantity));
    }

    @Override
    public void restock(String resourceId, long quantity) {
        AtomicLong avail = available.computeIfAbsent(resourceId, k -> new AtomicLong(0));
        AtomicLong tot = total.computeIfAbsent(resourceId, k -> new AtomicLong(0));
        avail.addAndGet(quantity);
        tot.addAndGet(quantity);
        deactivated.remove(resourceId);
    }

    @Override
    public void deactivate(String resourceId) {
        deactivated.put(resourceId, Boolean.TRUE);
    }

    @Override
    public Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests) {
        Map<String, ReservationResult> results = new HashMap<>();
        // Sort để tránh deadlock — dù in-memory không cần, nhưng giữ đúng contract
        requests.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> results.put(e.getKey(), reserve(e.getKey(), "batch", e.getValue())));
        return results;
    }

    @Override
    public void releaseBatch(Map<String, Integer> releases) {
        releases.forEach((resourceId, qty) -> release(resourceId, "batch", qty));
    }

    @Override
    public boolean isLowStock(String resourceId, long threshold) {
        return getAvailable(resourceId) <= threshold;
    }

    @Override
    public InventoryMetrics getMetrics() {
        return InventoryMetrics.NO_OP;
    }

    @Override
    public String getStrategyName() {
        return "in-memory";
    }

    @Override
    public ConsistencyLevel getConsistencyLevel() {
        return ConsistencyLevel.STRONG;
    }

    // =========================================================================
    // Tiện ích kiểm thử
    // =========================================================================

    /** Số lượng inventory hiện tại — dùng để assert trong test. */
    public long getCurrentAvailable(String resourceId) {
        return getAvailable(resourceId);
    }

    /** Số lần reserve() được gọi (kể cả thành công và thất bại). */
    public long getReserveCallCount(String resourceId) {
        AtomicLong counter = reserveCallCount.get(resourceId);
        return counter == null ? 0L : counter.get();
    }

    /** Số lần reserve bị từ chối do không đủ inventory (oversell attempt). */
    public long getOversellAttemptCount(String resourceId) {
        AtomicLong counter = oversellAttemptCount.get(resourceId);
        return counter == null ? 0L : counter.get();
    }

    /** Reset toàn bộ state — dùng giữa các test case. */
    public void reset() {
        available.clear();
        total.clear();
        deactivated.clear();
        reserveCallCount.clear();
        oversellAttemptCount.clear();
    }
}
