package io.hrc.product.order.config;

import io.hrc.autoconfigure.HcrProperties;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.factory.InventoryStrategyFactory;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.product.order.domain.ConcertTicket;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.inventory.InventoryReconciler;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ms-order chạy reserve/release qua RedisAtomicStrategy share Redis với ms-inventory (Path A).
 *
 * <p>InventoryStrategy chỉ cần Redis ops (reserve/release/getAvailable) — entityManager
 * + ConcertTicket.class chỉ được dùng nếu gọi {@code initialize()}, mà ms-order không gọi.
 *
 * <p>InventoryReconciler được tạo manual để inject vào TicketReconciliationService — nó query DB
 * inventory_db nhưng vì ms-order không own bảng đó nên reconcile chỉ chạy khi
 * {@code getResourceIdsToReconcile()} non-empty (TicketReconciliationService trả empty).
 */
@Configuration
public class OrderConfiguration {

    @Bean
    public InventoryStrategy inventoryStrategy(EntityManager entityManager,
                                                TransactionTemplate transactionTemplate,
                                                ApplicationEventPublisher eventPublisher,
                                                FrameworkMetrics frameworkMetrics,
                                                HcrProperties hcrProperties,
                                                EventBus eventBus,
                                                StringRedisTemplate redisTemplate) {
        InventoryStrategyFactory factory = new InventoryStrategyFactory(
                entityManager,
                ConcertTicket.class,
                transactionTemplate,
                eventPublisher,
                frameworkMetrics,
                redisTemplate,
                eventBus);
        return factory.create(hcrProperties.getInventory().getStrategy());
    }

    @Bean
    public InventoryReconciler inventoryReconciler(InventoryStrategy inventoryStrategy,
                                                    StringRedisTemplate redisTemplate,
                                                    EntityManager entityManager,
                                                    EventBus eventBus,
                                                    ReconciliationMetrics reconciliationMetrics) {
        return new InventoryReconciler(
                inventoryStrategy,
                redisTemplate,
                entityManager,
                ConcertTicket.class,
                eventBus,
                reconciliationMetrics,
                0L);
    }
}
