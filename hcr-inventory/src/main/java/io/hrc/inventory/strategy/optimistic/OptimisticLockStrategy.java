package io.hrc.inventory.strategy.optimistic;

import io.hrc.core.enums.ConsistencyLevel;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.result.InventorySnapshot;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.event.ResourceDepletedEvent;
import io.hrc.inventory.event.ResourceLowStockEvent;
import io.hrc.inventory.event.ResourceReleasedEvent;
import io.hrc.inventory.event.ResourceReservedEvent;
import io.hrc.inventory.event.ResourceRestockedEvent;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.inventory.strategy.InventoryStrategy;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * P2 — Optimistic Lock Strategy.
 *
 * <p>Cơ chế: không lock khi đọc. Khi ghi, kiểm tra {@code version} field —
 * nếu version thay đổi (thread khác đã update trước) → retry với exponential backoff + jitter.
 *
 * <p>Throughput: 1000–5000 req/s | Consistency: STRONG (0ms window).
 *
 * <p><b>Refactored:</b> Dùng {@link EntityManager} thay vì InventoryRecordRepository
 * → thao tác trực tiếp trên bảng developer (entity extend {@link AbstractInventoryEntity}).
 */
@Slf4j
public class OptimisticLockStrategy implements InventoryStrategy {

    private static final String STRATEGY_NAME = "optimistic";

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final InventoryMetrics metrics;
    private final int maxRetries;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final Random random = new Random();

    public OptimisticLockStrategy(EntityManager entityManager,
                                   Class<? extends AbstractInventoryEntity> entityClass,
                                   TransactionTemplate transactionTemplate,
                                   ApplicationEventPublisher eventPublisher,
                                   InventoryMetrics metrics,
                                   int maxRetries,
                                   long baseDelayMs,
                                   long maxDelayMs) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
    }

    // =========================================================================
    // Core Operations
    // =========================================================================

    @Override
    public ReservationResult reserve(String resourceId, String requestId, int quantity) {
        metrics.recordReserveAttempt(resourceId, STRATEGY_NAME);
        long start = System.currentTimeMillis();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                ReservationResult result = doReserveInTransaction(resourceId, requestId, quantity);

                if (result.isInsufficient()) {
                    metrics.recordReserveFailure(resourceId, STRATEGY_NAME, FailureReason.INSUFFICIENT_INVENTORY);
                    metrics.recordOversellPrevented(resourceId);
                    return result;
                }

                metrics.recordReserveSuccess(resourceId, STRATEGY_NAME, System.currentTimeMillis() - start);
                metrics.updateAvailableGauge(resourceId, result.getRemainingAfter());
                return result;

            } catch (OptimisticLockingFailureException e) {
                if (attempt == maxRetries) {
                    log.warn("[P2] reserve() failed after {} retries: resourceId={}", maxRetries, resourceId);
                    metrics.recordReserveFailure(resourceId, STRATEGY_NAME, FailureReason.SYSTEM_ERROR);
                    return ReservationResult.error(resourceId, quantity,
                        "Conflict liên tục sau " + maxRetries + " lần retry");
                }
                long delay = computeBackoff(attempt);
                log.debug("[P2] Conflict attempt={}, retry in {}ms: resourceId={}", attempt, delay, resourceId);
                sleep(delay);
            }
        }

        return ReservationResult.error(resourceId, quantity, "Unreachable");
    }

    /**
     * Thực hiện reserve trong một transaction mới.
     * Mỗi retry PHẢI là transaction mới — nếu dùng lại transaction cũ, Hibernate
     * vẫn giữ version cũ trong session cache và sẽ fail mãi mãi.
     */
    private ReservationResult doReserveInTransaction(String resourceId, String requestId, int quantity) {
        return transactionTemplate.execute(status -> {
            AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
            if (entity == null) {
                throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
            }

            if (entity.getAvailable() < quantity) {
                return ReservationResult.insufficient(resourceId, quantity);
            }

            long newAvailable = entity.getAvailable() - quantity;
            entity.setAvailable(newAvailable);
            entity.setUpdatedAt(Instant.now());
            entityManager.merge(entity);
            // flush để trigger version check ngay — nếu conflict thì ném exception ở đây
            entityManager.flush();

            if (newAvailable == 0) {
                eventPublisher.publishEvent(new ResourceDepletedEvent(resourceId, requestId));
            } else if (entity.isLowStock()) {
                eventPublisher.publishEvent(new ResourceLowStockEvent(
                    resourceId, newAvailable, entity.getLowStockThreshold(), requestId));
            }

            eventPublisher.publishEvent(
                new ResourceReservedEvent(resourceId, null, requestId, quantity, newAvailable, requestId));

            return ReservationResult.success(resourceId, quantity, newAvailable);
        });
    }

    @Override
    public void release(String resourceId, String requestId, int quantity) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                transactionTemplate.execute(status -> {
                    AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
                    if (entity == null) {
                        throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
                    }
                    long newAvailable = entity.getAvailable() + quantity;
                    entity.setAvailable(newAvailable);
                    entity.setUpdatedAt(Instant.now());
                    entityManager.merge(entity);
                    entityManager.flush();

                    eventPublisher.publishEvent(
                        new ResourceReleasedEvent(resourceId, null, requestId, quantity, newAvailable, requestId));
                    return null;
                });

                metrics.recordReleaseSuccess(resourceId, STRATEGY_NAME);
                metrics.updateAvailableGauge(resourceId, getAvailable(resourceId));
                return;

            } catch (OptimisticLockingFailureException e) {
                if (attempt == maxRetries) {
                    throw new RuntimeException("Release failed sau " + maxRetries + " retry: " + resourceId, e);
                }
                sleep(computeBackoff(attempt));
            }
        }
    }

    // =========================================================================
    // Query & Management
    // =========================================================================

    @Override
    public long getAvailable(String resourceId) {
        AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
        return entity != null ? entity.getAvailable() : 0L;
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
        AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
        if (entity == null) return null;
        return InventorySnapshot.builder()
            .resourceId(entity.getResourceId())
            .totalQuantity(entity.getTotal())
            .availableQuantity(entity.getAvailable())
            .reservedQuantity(0L)
            .confirmedQuantity(entity.getTotal() - entity.getAvailable())
            .snapshotAt(Instant.now())
            .source("database")
            .build();
    }

    @Override
    public void initialize(String resourceId, long totalQuantity) {
        if (entityManager.find(entityClass, resourceId) != null) {
            throw new IllegalStateException("Resource đã được khởi tạo: " + resourceId);
        }
        try {
            AbstractInventoryEntity entity = entityClass.getDeclaredConstructor(
                String.class, long.class).newInstance(resourceId, totalQuantity);
            entityManager.persist(entity);
            log.info("[P2] Initialized inventory: resourceId={}, total={}", resourceId, totalQuantity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Entity class " + entityClass.getSimpleName() +
                " phải có constructor(String resourceId, long total)", e);
        }
    }

    @Override
    public void restock(String resourceId, long quantity) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                transactionTemplate.execute(status -> {
                    AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
                    if (entity == null) {
                        throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
                    }
                    entity.setAvailable(entity.getAvailable() + quantity);
                    entity.setTotal(entity.getTotal() + quantity);
                    entity.setUpdatedAt(Instant.now());
                    entityManager.merge(entity);
                    entityManager.flush();
                    eventPublisher.publishEvent(
                        new ResourceRestockedEvent(resourceId, quantity, entity.getAvailable(), null));
                    return null;
                });
                return;
            } catch (OptimisticLockingFailureException e) {
                if (attempt == maxRetries) throw e;
                sleep(computeBackoff(attempt));
            }
        }
    }

    @Override
    public void deactivate(String resourceId) {
        transactionTemplate.execute(status -> {
            AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
            if (entity == null) {
                throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
            }
            entity.setAvailable(-1);
            entity.setUpdatedAt(Instant.now());
            entityManager.merge(entity);
            return null;
        });
    }

    @Override
    public Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests) {
        Map<String, ReservationResult> results = new HashMap<>();
        new TreeMap<>(requests).forEach((resourceId, qty) ->
            results.put(resourceId, reserve(resourceId, "batch", qty)));
        return results;
    }

    @Override
    public void releaseBatch(Map<String, Integer> releases) {
        new TreeMap<>(releases).forEach((resourceId, qty) ->
            release(resourceId, "batch-release", qty));
    }

    @Override
    public boolean isLowStock(String resourceId, long threshold) {
        return getAvailable(resourceId) <= threshold;
    }

    @Override
    public InventoryMetrics getMetrics() {
        return metrics;
    }

    @Override
    public String getStrategyName() {
        return STRATEGY_NAME;
    }

    @Override
    public ConsistencyLevel getConsistencyLevel() {
        return ConsistencyLevel.STRONG;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private long computeBackoff(int attempt) {
        long exponential = Math.min(baseDelayMs * (1L << attempt), maxDelayMs);
        long jitter = random.nextLong(baseDelayMs);
        return exponential + jitter;
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
