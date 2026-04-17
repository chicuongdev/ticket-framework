package io.hrc.sample.config;

import io.hrc.autoconfigure.HcrProperties;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.factory.InventoryStrategyFactory;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.sample.domain.ConcertTicket;
import jakarta.persistence.EntityManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cấu hình bean cho hcr-sample.
 *
 * <p>InventoryStrategy KHÔNG được auto-configure vì cần {@code entityClass} cụ thể
 * của developer — framework không thể đoán được. Developer phải khai báo bean này thủ công.
 */
@Configuration
public class SampleConfiguration {

    @Bean
    public InventoryStrategy inventoryStrategy(EntityManager entityManager,
                                                TransactionTemplate transactionTemplate,
                                                ApplicationEventPublisher eventPublisher,
                                                FrameworkMetrics frameworkMetrics,
                                                HcrProperties hcrProperties,
                                                EventBus eventBus) {
        InventoryStrategyFactory factory = new InventoryStrategyFactory(
                entityManager,
                ConcertTicket.class,
                transactionTemplate,
                eventPublisher,
                frameworkMetrics,
                null,   // RedisTemplate — không cần cho pessimistic-lock
                eventBus);

        return factory.create(hcrProperties.getInventory().getStrategy());
    }
}
