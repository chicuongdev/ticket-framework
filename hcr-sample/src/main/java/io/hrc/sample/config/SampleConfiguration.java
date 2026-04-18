package io.hrc.sample.config;

import io.hrc.autoconfigure.HcrProperties;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.factory.InventoryStrategyFactory;
import io.hrc.inventory.persistence.BatchInventoryPersistenceConsumer;
import io.hrc.inventory.persistence.InventoryPersistenceConsumer;
import io.hrc.inventory.persistence.PersistenceConfig;
import io.hrc.inventory.persistence.PersistenceMode;
import io.hrc.inventory.persistence.ProcessedEventRepository;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.sample.domain.ConcertTicket;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Cấu hình bean cho hcr-sample.
 *
 * <p>InventoryStrategy KHÔNG được auto-configure vì cần {@code entityClass} cụ thể
 * của developer — framework không thể đoán được. Developer phải khai báo bean này thủ công.
 *
 * <p>Khi strategy=redis-atomic, developer cũng phải khai báo
 * {@link InventoryPersistenceConsumer} (single mode) hoặc
 * {@link BatchInventoryPersistenceConsumer} (batch mode) để đồng bộ Redis → DB.
 * Bean tự subscribe vào EventBus qua {@code InitializingBean}.
 */
@Configuration
public class SampleConfiguration {

    @Bean
    public InventoryStrategy inventoryStrategy(EntityManager entityManager,
                                                TransactionTemplate transactionTemplate,
                                                ApplicationEventPublisher eventPublisher,
                                                FrameworkMetrics frameworkMetrics,
                                                HcrProperties hcrProperties,
                                                EventBus eventBus,
                                                @Autowired(required = false)
                                                StringRedisTemplate redisTemplate) {
        InventoryStrategyFactory factory = new InventoryStrategyFactory(
                entityManager,
                ConcertTicket.class,
                transactionTemplate,
                eventPublisher,
                frameworkMetrics,
                redisTemplate,   // null khi P1/P2, có giá trị khi P3
                eventBus);

        return factory.create(hcrProperties.getInventory().getStrategy());
    }

    /**
     * Single-mode consumer — chỉ active khi strategy=redis-atomic và mode=single (default).
     * Tự subscribe vào EventBus qua InitializingBean.
     */
    @Bean
    @ConditionalOnExpression(
            "'${hcr.inventory.strategy:pessimistic-lock}' == 'redis-atomic' " +
            "&& '${hcr.inventory.persistence.mode:single}' == 'single'")
    public InventoryPersistenceConsumer singlePersistenceConsumer(
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

    /**
     * Batch-mode consumer — chỉ active khi strategy=redis-atomic và mode=batch.
     * Tự subscribe + flush remaining buffer khi destroy.
     */
    @Bean
    @ConditionalOnExpression(
            "'${hcr.inventory.strategy:pessimistic-lock}' == 'redis-atomic' " +
            "&& '${hcr.inventory.persistence.mode:single}' == 'batch'")
    public BatchInventoryPersistenceConsumer batchPersistenceConsumer(
            EntityManager entityManager,
            ProcessedEventRepository processedEventRepository,
            TransactionTemplate transactionTemplate,
            HcrProperties hcrProperties,
            EventBus eventBus) {
        HcrProperties.PersistenceProperties props = hcrProperties.getInventory().getPersistence();
        PersistenceConfig config = PersistenceConfig.builder()
                .mode(PersistenceMode.BATCH)
                .batchSize(props.getBatchSize())
                .flushIntervalMs(props.getFlushIntervalMs())
                .build();
        return new BatchInventoryPersistenceConsumer(
                entityManager,
                ConcertTicket.class,
                processedEventRepository,
                transactionTemplate,
                config,
                eventBus);
    }
}
