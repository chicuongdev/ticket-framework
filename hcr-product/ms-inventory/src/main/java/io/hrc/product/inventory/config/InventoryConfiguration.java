package io.hrc.product.inventory.config;

import io.hrc.autoconfigure.HcrProperties;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.factory.InventoryStrategyFactory;
import io.hrc.inventory.persistence.InventoryPersistenceConsumer;
import io.hrc.inventory.persistence.ProcessedEventRepository;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.product.inventory.domain.ConcertTicket;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ms-inventory không phục vụ HTTP request reserve/release — vai trò là persistence
 * consumer cho inventory events từ Kafka.
 *
 * <p>Bean {@link InventoryStrategy} chỉ được dùng bởi {@code RedisInventorySeeder} để
 * gọi {@code initialize()} cho từng resourceId lúc startup. Reserve/release chạy
 * tại ms-order qua cùng instance Redis.
 */
@Configuration
public class InventoryConfiguration {

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
    public InventoryPersistenceConsumer inventoryPersistenceConsumer(
            EntityManager entityManager,
            ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate,
            EventBus eventBus) {
        return new InventoryPersistenceConsumer(
                entityManager,
                ConcertTicket.class,
                processedEventRepository,
                transactionTemplate,
                eventBus);
    }
}
