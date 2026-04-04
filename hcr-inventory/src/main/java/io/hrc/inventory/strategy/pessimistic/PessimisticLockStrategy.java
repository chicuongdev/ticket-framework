package io.hrc.inventory.strategy.pessimistic;

import io.hrc.core.enums.ConsistencyLevel;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.result.InventorySnapshot;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.event.*;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.inventory.strategy.InventoryStrategy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * P1 — Pessimistic Lock Strategy.
 *
 * <p>Cơ chế: {@code SELECT FOR UPDATE} lock row trước khi đọc.
 * Thread khác gọi {@link #reserve} với cùng resourceId sẽ bị BLOCK
 * cho đến khi transaction này commit hoặc rollback.
 *
 * <p>Throughput: ~1000 req/s | Consistency: STRONG (0ms window).
 *
 * <p><b>Deadlock prevention:</b> Trong {@link #reserveBatch}, các key được
 * lock theo thứ tự alphabet — đảm bảo tất cả thread lock theo cùng một thứ tự,
 * loại bỏ circular wait condition.
 *
 * <p><b>Refactored:</b> Dùng {@link EntityManager} thay vì InventoryRecordRepository
 * → thao tác trực tiếp trên bảng developer (entity extend {@link AbstractInventoryEntity}).
 */
@Slf4j
public class PessimisticLockStrategy implements InventoryStrategy {

    private static final String STRATEGY_NAME = "pessimistic";

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final InventoryMetrics metrics;

    public PessimisticLockStrategy(EntityManager entityManager,
                                    Class<? extends AbstractInventoryEntity> entityClass,
                                    TransactionTemplate transactionTemplate,
                                    ApplicationEventPublisher eventPublisher,
                                    InventoryMetrics metrics) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
    }

    // =========================================================================
    // Core Operations
    // =========================================================================

    @Override
    public ReservationResult reserve(String resourceId, String requestId, int quantity) {
        metrics.recordReserveAttempt(resourceId, STRATEGY_NAME);
        long start = System.currentTimeMillis();

        ReservationResult result = transactionTemplate.execute(status -> {
            AbstractInventoryEntity entity = entityManager.find(
                entityClass, resourceId, LockModeType.PESSIMISTIC_WRITE);

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

        long duration = System.currentTimeMillis() - start;

        if (result == null || result.isError()) {
            metrics.recordReserveFailure(resourceId, STRATEGY_NAME, FailureReason.SYSTEM_ERROR);
            return result != null ? result : ReservationResult.error(resourceId, quantity, "Transaction returned null");
        }
        if (result.isInsufficient()) {
            metrics.recordReserveFailure(resourceId, STRATEGY_NAME, FailureReason.INSUFFICIENT_INVENTORY);
            metrics.recordOversellPrevented(resourceId);
            return result;
        }

        metrics.recordReserveSuccess(resourceId, STRATEGY_NAME, duration);
        metrics.updateAvailableGauge(resourceId, result.getRemainingAfter());
        return result;
    }

    @Override
    public void release(String resourceId, String requestId, int quantity) {
        transactionTemplate.execute(status -> {
            AbstractInventoryEntity entity = entityManager.find(
                entityClass, resourceId, LockModeType.PESSIMISTIC_WRITE);

            if (entity == null) {
                throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
            }

            long newAvailable = entity.getAvailable() + quantity;
            entity.setAvailable(newAvailable);
            entity.setUpdatedAt(Instant.now());
            entityManager.merge(entity);

            eventPublisher.publishEvent(
                new ResourceReleasedEvent(resourceId, null, requestId, quantity, newAvailable, requestId));

            return null;
        });

        metrics.recordReleaseSuccess(resourceId, STRATEGY_NAME);
        metrics.updateAvailableGauge(resourceId, getAvailable(resourceId));
    }

    // =========================================================================
    // Query Operations
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

    // =========================================================================
    // Management Operations
    // =========================================================================

    @Override
    public void initialize(String resourceId, long totalQuantity) {
        AbstractInventoryEntity existing = entityManager.find(entityClass, resourceId);
        if (existing != null) {
            throw new IllegalStateException("Resource đã được khởi tạo: " + resourceId);
        }
        try {
            AbstractInventoryEntity entity = entityClass.getDeclaredConstructor(
                String.class, long.class).newInstance(resourceId, totalQuantity);
            entityManager.persist(entity);
            log.info("[P1] Initialized inventory: resourceId={}, total={}", resourceId, totalQuantity);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                "Entity class " + entityClass.getSimpleName() +
                " phải có constructor(String resourceId, long total)", e);
        }
    }

    @Override
    public void restock(String resourceId, long quantity) {
        transactionTemplate.execute(status -> {
            AbstractInventoryEntity entity = entityManager.find(
                entityClass, resourceId, LockModeType.PESSIMISTIC_WRITE);
            if (entity == null) {
                throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
            }
            entity.setAvailable(entity.getAvailable() + quantity);
            entity.setTotal(entity.getTotal() + quantity);
            entity.setUpdatedAt(Instant.now());
            entityManager.merge(entity);

            eventPublisher.publishEvent(
                new ResourceRestockedEvent(resourceId, quantity, entity.getAvailable(), null));
            return null;
        });
    }

    @Override
    public void deactivate(String resourceId) {
        transactionTemplate.execute(status -> {
            AbstractInventoryEntity entity = entityManager.find(
                entityClass, resourceId, LockModeType.PESSIMISTIC_WRITE);
            if (entity == null) {
                throw new IllegalArgumentException("Resource không tồn tại: " + resourceId);
            }
            entity.setAvailable(-1);
            entity.setUpdatedAt(Instant.now());
            entityManager.merge(entity);
            return null;
        });
        log.info("[P1] Deactivated resource: {}", resourceId);
    }

    // =========================================================================
    // Bulk Operations — deadlock-safe với ordered locking
    // =========================================================================

    @Override
    public Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests) {
        Map<String, ReservationResult> results = new HashMap<>();
        Map<String, Integer> ordered = new TreeMap<>(requests);

        transactionTemplate.execute(status -> {
            for (Map.Entry<String, Integer> entry : ordered.entrySet()) {
                ReservationResult r = reserve(entry.getKey(), "batch", entry.getValue());
                results.put(entry.getKey(), r);
                if (!r.isSuccess()) {
                    status.setRollbackOnly();
                    break;
                }
            }
            return null;
        });

        return results;
    }

    @Override
    public void releaseBatch(Map<String, Integer> releases) {
        transactionTemplate.execute(status -> {
            new TreeMap<>(releases).forEach((resourceId, qty) ->
                release(resourceId, "batch-release", qty));
            return null;
        });
    }

    // =========================================================================
    // Monitoring
    // =========================================================================

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
}
