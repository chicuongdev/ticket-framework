package io.hrc.inventory.persistence;

import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.EventHandler;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.event.ResourceReleasedEvent;
import io.hrc.inventory.event.ResourceReservedEvent;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Idempotent consumer — đồng bộ DB sau khi Redis đã xử lý xong (P3 only).
 *
 * <p><b>Tự động subscribe/unsubscribe</b> qua Spring lifecycle:
 * <ul>
 *   <li>{@link InitializingBean#afterPropertiesSet()} → subscribe handlers vào EventBus.</li>
 *   <li>{@link DisposableBean#destroy()} → unsubscribe khi bean bị shutdown.</li>
 * </ul>
 *
 * <p><b>Luồng xử lý:</b>
 * <pre>
 * EventBus deliver ResourceReservedEvent
 *   → Consumer nhận event
 *   → BEGIN TRANSACTION
 *     → INSERT INTO hcr_processed_events (event_id, ...)
 *       → Nếu duplicate key → event đã xử lý → skip → ACK
 *     → UPDATE developer_table SET available = available - qty WHERE resource_id = ?
 *   → COMMIT
 *   → ACK
 * </pre>
 */
@Slf4j
public class InventoryPersistenceConsumer implements InitializingBean, DisposableBean {

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final EventBus eventBus;

    private EventHandler<ResourceReservedEvent> reservedHandler;
    private EventHandler<ResourceReleasedEvent> releasedHandler;

    public InventoryPersistenceConsumer(EntityManager entityManager,
                                        Class<? extends AbstractInventoryEntity> entityClass,
                                        ProcessedEventRepository processedEventRepository,
                                        TransactionTemplate transactionTemplate,
                                        EventBus eventBus) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.eventBus = eventBus;
    }

    // =========================================================================
    // Spring lifecycle — auto subscribe/unsubscribe
    // =========================================================================

    @Override
    public void afterPropertiesSet() {
        this.reservedHandler = buildReservedHandler();
        this.releasedHandler = buildReleasedHandler();
        eventBus.subscribe(ResourceReservedEvent.class, reservedHandler);
        eventBus.subscribe(ResourceReleasedEvent.class, releasedHandler);
        log.info("[P3-Consumer] Subscribed to ResourceReservedEvent + ResourceReleasedEvent");
    }

    @Override
    public void destroy() {
        if (reservedHandler != null) {
            eventBus.unsubscribe(ResourceReservedEvent.class, reservedHandler);
        }
        if (releasedHandler != null) {
            eventBus.unsubscribe(ResourceReleasedEvent.class, releasedHandler);
        }
        log.info("[P3-Consumer] Unsubscribed from EventBus");
    }

    // =========================================================================
    // Handler factories — public để testing có thể gọi trực tiếp
    // =========================================================================

    /**
     * Handler cho ResourceReservedEvent — trừ available trong DB.
     */
    public EventHandler<ResourceReservedEvent> buildReservedHandler() {
        return (event, ack) -> {
            String eventId = event.getEventId();
            String resourceId = event.getResourceId();
            int quantity = event.getQuantity();

            log.debug("[P3-Consumer] Received reserve event: eventId={}, resourceId={}, qty={}",
                eventId, resourceId, quantity);

            boolean processed = processIdempotent(eventId, "ResourceReservedEvent", () -> {
                AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
                if (entity == null) {
                    log.warn("[P3-Consumer] Resource not found in DB: {}", resourceId);
                    return;
                }
                entity.setAvailable(entity.getAvailable() - quantity);
                entity.setUpdatedAt(Instant.now());
                entityManager.merge(entity);
            });

            if (processed) {
                log.debug("[P3-Consumer] DB synced OK: resourceId={}, qty=-{}", resourceId, quantity);
            } else {
                log.debug("[P3-Consumer] Duplicate event skipped: eventId={}", eventId);
            }

            ack.acknowledge();
        };
    }

    /**
     * Handler cho ResourceReleasedEvent — cộng available trong DB.
     */
    public EventHandler<ResourceReleasedEvent> buildReleasedHandler() {
        return (event, ack) -> {
            String eventId = event.getEventId();
            String resourceId = event.getResourceId();
            int quantity = event.getQuantity();

            log.debug("[P3-Consumer] Received release event: eventId={}, resourceId={}, qty={}",
                eventId, resourceId, quantity);

            boolean processed = processIdempotent(eventId, "ResourceReleasedEvent", () -> {
                AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
                if (entity == null) {
                    log.warn("[P3-Consumer] Resource not found in DB: {}", resourceId);
                    return;
                }
                entity.setAvailable(entity.getAvailable() + quantity);
                entity.setUpdatedAt(Instant.now());
                entityManager.merge(entity);
            });

            if (processed) {
                log.debug("[P3-Consumer] DB release synced OK: resourceId={}, qty=+{}", resourceId, quantity);
            } else {
                log.debug("[P3-Consumer] Duplicate event skipped: eventId={}", eventId);
            }

            ack.acknowledge();
        };
    }

    /**
     * Xử lý event với idempotency guard.
     *
     * <p>INSERT hcr_processed_events + business logic trong cùng 1 transaction.
     * Nếu eventId đã tồn tại → DataIntegrityViolationException → skip → return false.
     */
    private boolean processIdempotent(String eventId, String eventType, Runnable action) {
        try {
            transactionTemplate.execute(status -> {
                processedEventRepository.save(new ProcessedEvent(eventId, eventType));
                action.run();
                return null;
            });
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }
}
