package io.hrc.inventory.strategy.redis;

import io.hrc.core.enums.ConsistencyLevel;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.result.InventorySnapshot;
import io.hrc.core.result.ReservationResult;
import io.hrc.eventbus.EventBus;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 — Redis Atomic Strategy.
 *
 * <p><b>Critical path</b> (&lt;5ms): Lua script atomic trên Redis → publish event → return.
 * DB không nằm trong critical path — được đồng bộ async qua {@link EventBus}.
 *
 * <p><b>Refactored (2 thay đổi):</b>
 * <ul>
 *   <li><b>V1:</b> Dùng {@link EntityManager} thay vì InventoryRecordRepository
 *       → thao tác trực tiếp trên bảng developer.</li>
 *   <li><b>V2A:</b> Dùng {@link EventBus} (Kafka/RabbitMQ/Redis Streams) thay vì
 *       Spring {@code @EventListener} cho DB sync path → message persistent,
 *       crash-safe, auto-redeliver.</li>
 * </ul>
 *
 * <p><b>Known limitation:</b> Gap giữa "Redis DECR thành công" và "EventBus.publish()"
 * — nếu crash ở giữa, event mất. Reconciliation sẽ phát hiện mismatch Redis vs DB
 * trong ≤ 5 phút.
 */
@Slf4j
public class RedisAtomicStrategy implements InventoryStrategy {

    private static final String STRATEGY_NAME = "redis-atomic";
    private static final String KEY_PREFIX = "hcr:inventory:";
    private static final String TOTAL_KEY_PREFIX = "hcr:inventory:total:";

    private static final long LUA_KEY_NOT_INITIALIZED = -1L;
    private static final long LUA_INSUFFICIENT = -2L;

    private final StringRedisTemplate redisTemplate;
    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final ApplicationEventPublisher eventPublisher;
    private final EventBus eventBus;
    private final InventoryMetrics metrics;

    private final DefaultRedisScript<Long> reserveScript;
    private final DefaultRedisScript<Long> releaseScript;

    public RedisAtomicStrategy(StringRedisTemplate redisTemplate,
                                EntityManager entityManager,
                                Class<? extends AbstractInventoryEntity> entityClass,
                                ApplicationEventPublisher eventPublisher,
                                EventBus eventBus,
                                InventoryMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.eventPublisher = eventPublisher;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.reserveScript = buildScript("lua/inventory_reserve.lua");
        this.releaseScript = buildScript("lua/inventory_release.lua");
    }

    // =========================================================================
    // Core Operations — critical path
    // =========================================================================

    @Override
    public ReservationResult reserve(String resourceId, String requestId, int quantity) {
        metrics.recordReserveAttempt(resourceId, STRATEGY_NAME);
        long start = System.currentTimeMillis();

        String key = KEY_PREFIX + resourceId;
        Long result = redisTemplate.execute(reserveScript, List.of(key), String.valueOf(quantity));

        if (result == null || result == LUA_KEY_NOT_INITIALIZED) {
            log.error("[P3] Redis key not initialized for resourceId={}. " +
                      "Run InventoryInitializer first.", resourceId);
            metrics.recordReserveFailure(resourceId, STRATEGY_NAME, FailureReason.SYSTEM_ERROR);
            return ReservationResult.error(resourceId, quantity,
                "Redis key chưa được khởi tạo: " + resourceId);
        }

        if (result == LUA_INSUFFICIENT) {
            metrics.recordReserveFailure(resourceId, STRATEGY_NAME, FailureReason.INSUFFICIENT_INVENTORY);
            metrics.recordOversellPrevented(resourceId);
            return ReservationResult.insufficient(resourceId, quantity);
        }

        long remaining = result;

        // Publish qua EventBus (persistent) cho DB sync — consumer sẽ update bảng developer
        ResourceReservedEvent event = new ResourceReservedEvent(
            resourceId, null, requestId, quantity, remaining, requestId);
        eventBus.publish(event);

        // Publish qua Spring event (in-memory) cho low stock / depleted notification
        if (remaining == 0) {
            metrics.recordDepleted(resourceId);
            eventPublisher.publishEvent(new ResourceDepletedEvent(resourceId, requestId));
        } else {
            long threshold = getLowStockThreshold(resourceId);
            if (threshold > 0 && remaining <= threshold) {
                metrics.recordLowStock(resourceId);
                eventPublisher.publishEvent(
                    new ResourceLowStockEvent(resourceId, remaining, threshold, requestId));
            }
        }

        long duration = System.currentTimeMillis() - start;
        metrics.recordReserveSuccess(resourceId, STRATEGY_NAME, duration);
        metrics.updateAvailableGauge(resourceId, remaining);

        return ReservationResult.success(resourceId, quantity, remaining);
    }

    @Override
    public void release(String resourceId, String requestId, int quantity) {
        String key = KEY_PREFIX + resourceId;
        String totalKey = TOTAL_KEY_PREFIX + resourceId;

        String totalStr = redisTemplate.opsForValue().get(totalKey);
        long total = totalStr != null ? Long.parseLong(totalStr) : Long.MAX_VALUE;

        Long result = redisTemplate.execute(releaseScript,
            List.of(key), String.valueOf(quantity), String.valueOf(total));

        if (result == null || result == LUA_KEY_NOT_INITIALIZED) {
            log.error("[P3] Release failed — Redis key not initialized: {}", resourceId);
            return;
        }

        long newRemaining = result;

        // Publish qua EventBus (persistent) cho DB sync
        ResourceReleasedEvent event = new ResourceReleasedEvent(
            resourceId, null, requestId, quantity, newRemaining, requestId);
        eventBus.publish(event);

        metrics.recordReleaseSuccess(resourceId, STRATEGY_NAME);
        metrics.updateAvailableGauge(resourceId, newRemaining);
    }

    // =========================================================================
    // Query Operations — đọc từ Redis (source of truth cho P3)
    // =========================================================================

    @Override
    public long getAvailable(String resourceId) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + resourceId);
        if (value == null) return 0L;
        long v = Long.parseLong(value);
        return Math.max(0, v);
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
        long redisAvailable = getAvailable(resourceId);
        long total = 0;
        String totalStr = redisTemplate.opsForValue().get(TOTAL_KEY_PREFIX + resourceId);
        if (totalStr != null) total = Long.parseLong(totalStr);

        return InventorySnapshot.builder()
            .resourceId(resourceId)
            .totalQuantity(total)
            .availableQuantity(redisAvailable)
            .reservedQuantity(0L)
            .confirmedQuantity(total - redisAvailable)
            .snapshotAt(Instant.now())
            .source("redis")
            .build();
    }

    // =========================================================================
    // Management Operations
    // =========================================================================

    @Override
    public void initialize(String resourceId, long totalQuantity) {
        String key = KEY_PREFIX + resourceId;
        String totalKey = TOTAL_KEY_PREFIX + resourceId;

        Boolean set = redisTemplate.opsForValue().setIfAbsent(key, String.valueOf(totalQuantity));
        if (Boolean.FALSE.equals(set)) {
            throw new IllegalStateException("Redis key đã tồn tại: " + resourceId);
        }
        redisTemplate.opsForValue().set(totalKey, String.valueOf(totalQuantity));

        // Đồng bộ vào DB (bảng developer)
        if (entityManager.find(entityClass, resourceId) == null) {
            try {
                AbstractInventoryEntity entity = entityClass.getDeclaredConstructor(
                    String.class, long.class).newInstance(resourceId, totalQuantity);
                entityManager.persist(entity);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                    "Entity class " + entityClass.getSimpleName() +
                    " phải có constructor(String resourceId, long total)", e);
            }
        }
        log.info("[P3] Initialized inventory: resourceId={}, total={}", resourceId, totalQuantity);
    }

    @Override
    public void restock(String resourceId, long quantity) {
        String key = KEY_PREFIX + resourceId;
        String totalKey = TOTAL_KEY_PREFIX + resourceId;

        redisTemplate.opsForValue().increment(key, quantity);

        String totalStr = redisTemplate.opsForValue().get(totalKey);
        long newTotal = (totalStr != null ? Long.parseLong(totalStr) : 0) + quantity;
        redisTemplate.opsForValue().set(totalKey, String.valueOf(newTotal));

        eventPublisher.publishEvent(
            new ResourceRestockedEvent(resourceId, quantity, getAvailable(resourceId), null));
    }

    @Override
    public void deactivate(String resourceId) {
        redisTemplate.opsForValue().set(KEY_PREFIX + resourceId, "-1");
        log.info("[P3] Deactivated resource: {}", resourceId);
    }

    // =========================================================================
    // Bulk Operations
    // =========================================================================

    @Override
    public Map<String, ReservationResult> reserveBatch(Map<String, Integer> requests) {
        Map<String, ReservationResult> results = new HashMap<>();
        requests.forEach((resourceId, qty) ->
            results.put(resourceId, reserve(resourceId, "batch", qty)));
        return results;
    }

    @Override
    public void releaseBatch(Map<String, Integer> releases) {
        releases.forEach((resourceId, qty) -> release(resourceId, "batch-release", qty));
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
        return ConsistencyLevel.EVENTUAL;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static DefaultRedisScript<Long> buildScript(String classpathLocation) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathLocation)));
        script.setResultType(Long.class);
        return script;
    }

    private long getLowStockThreshold(String resourceId) {
        AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
        return entity != null ? entity.getLowStockThreshold() : 0L;
    }
}
