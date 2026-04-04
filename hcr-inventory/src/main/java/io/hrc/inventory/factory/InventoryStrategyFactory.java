package io.hrc.inventory.factory;

import io.hrc.eventbus.EventBus;
import io.hrc.inventory.decorator.CircuitBreakerInventoryDecorator;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.inventory.strategy.optimistic.OptimisticLockStrategy;
import io.hrc.inventory.strategy.pessimistic.PessimisticLockStrategy;
import io.hrc.inventory.strategy.redis.RedisAtomicStrategy;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Tạo đúng {@link InventoryStrategy} dựa trên config yaml.
 *
 * <p><b>Refactored:</b> Nhận {@link EntityManager} + {@code entityClass} thay vì
 * InventoryRecordRepository. Strategy thao tác trực tiếp trên bảng developer.
 */
@Slf4j
public class InventoryStrategyFactory {

    public static final String PESSIMISTIC = "pessimistic-lock";
    public static final String OPTIMISTIC  = "optimistic-lock";
    public static final String REDIS       = "redis-atomic";

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final InventoryMetrics metrics;

    /** Optional — chỉ cần cho RedisAtomicStrategy. */
    private final StringRedisTemplate redisTemplate;

    /** Optional — chỉ cần cho RedisAtomicStrategy (V2A: persistent DB sync). */
    private final EventBus eventBus;

    /** Custom strategies đăng ký bởi developer. */
    private final Map<String, InventoryStrategy> customStrategies = new HashMap<>();

    public InventoryStrategyFactory(EntityManager entityManager,
                                     Class<? extends AbstractInventoryEntity> entityClass,
                                     TransactionTemplate transactionTemplate,
                                     ApplicationEventPublisher eventPublisher,
                                     InventoryMetrics metrics,
                                     StringRedisTemplate redisTemplate,
                                     EventBus eventBus) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.transactionTemplate = transactionTemplate;
        this.eventPublisher = eventPublisher;
        this.metrics = metrics;
        this.redisTemplate = redisTemplate;
        this.eventBus = eventBus;
    }

    public InventoryStrategy create(String strategyName,
                                     boolean circuitBreakerEnabled,
                                     CircuitBreakerConfig cbConfig) {
        InventoryStrategy strategy = buildStrategy(strategyName);
        log.info("[InventoryStrategyFactory] Created strategy: {}", strategy.getStrategyName());

        if (circuitBreakerEnabled) {
            strategy = wrapWithCircuitBreaker(strategy, cbConfig);
            log.info("[InventoryStrategyFactory] Wrapped with CircuitBreaker: {}", cbConfig);
        }

        return strategy;
    }

    public InventoryStrategy create(String strategyName) {
        return create(strategyName, false, null);
    }

    public void registerCustomStrategy(String name, InventoryStrategy strategy) {
        customStrategies.put(name, strategy);
        log.info("[InventoryStrategyFactory] Registered custom strategy: {}", name);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private InventoryStrategy buildStrategy(String name) {
        if (customStrategies.containsKey(name)) {
            return customStrategies.get(name);
        }

        return switch (name) {
            case PESSIMISTIC -> new PessimisticLockStrategy(
                entityManager, entityClass, transactionTemplate, eventPublisher, metrics);

            case OPTIMISTIC -> new OptimisticLockStrategy(
                entityManager, entityClass, transactionTemplate, eventPublisher, metrics,
                3, 100L, 1000L);

            case REDIS -> {
                if (redisTemplate == null) {
                    throw new IllegalStateException(
                        "RedisAtomicStrategy yêu cầu StringRedisTemplate bean.");
                }
                if (eventBus == null) {
                    throw new IllegalStateException(
                        "RedisAtomicStrategy yêu cầu EventBus bean cho persistent DB sync.");
                }
                yield new RedisAtomicStrategy(
                    redisTemplate, entityManager, entityClass,
                    eventPublisher, eventBus, metrics);
            }

            default -> throw new IllegalArgumentException(
                "Unknown inventory strategy: '" + name + "'. " +
                "Supported: pessimistic-lock, optimistic-lock, redis-atomic");
        };
    }

    private InventoryStrategy wrapWithCircuitBreaker(InventoryStrategy strategy,
                                                      CircuitBreakerConfig cfg) {
        return new CircuitBreakerInventoryDecorator(
            strategy,
            cfg.getSlidingWindowSize(),
            cfg.getFailureRateThreshold() / 100.0,
            cfg.getWaitDurationSeconds() * 1000L
        );
    }

    public static class CircuitBreakerConfig {
        private int slidingWindowSize = 10;
        private double failureRateThreshold = 50.0;
        private long waitDurationSeconds = 60;

        public CircuitBreakerConfig() {}

        public CircuitBreakerConfig(int slidingWindowSize, double failureRateThreshold,
                                     long waitDurationSeconds) {
            this.slidingWindowSize = slidingWindowSize;
            this.failureRateThreshold = failureRateThreshold;
            this.waitDurationSeconds = waitDurationSeconds;
        }

        public int getSlidingWindowSize() { return slidingWindowSize; }
        public double getFailureRateThreshold() { return failureRateThreshold; }
        public long getWaitDurationSeconds() { return waitDurationSeconds; }

        @Override
        public String toString() {
            return "CircuitBreakerConfig{window=" + slidingWindowSize +
                   ", threshold=" + failureRateThreshold + "%, wait=" + waitDurationSeconds + "s}";
        }
    }
}
