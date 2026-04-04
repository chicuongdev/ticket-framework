package io.hrc.inventory.persistence;

import io.hrc.eventbus.Acknowledgment;
import io.hrc.eventbus.EventHandler;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.event.ResourceReleasedEvent;
import io.hrc.inventory.event.ResourceReservedEvent;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * Idempotent consumer — đồng bộ DB sau khi Redis đã xử lý xong (P3 only).
 *
 * <p><b>Thay đổi so với phiên bản cũ:</b>
 * <ul>
 *   <li>Dùng {@link io.hrc.eventbus.EventBus} thay vì Spring {@code @EventListener}
 *       → message persistent, crash-safe, auto-redeliver.</li>
 *   <li>Idempotency qua {@link ProcessedEvent} + eventId thay vì
 *       {@code WHERE available >= delta} (chỉ tránh trừ âm, không tránh trừ 2 lần).</li>
 *   <li>Dùng {@link EntityManager} thay vì InventoryRecordRepository
 *       → update trực tiếp trên bảng developer.</li>
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
public class InventoryPersistenceConsumer {

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;

    public InventoryPersistenceConsumer(EntityManager entityManager,
                                        Class<? extends AbstractInventoryEntity> entityClass,
                                        ProcessedEventRepository processedEventRepository,
                                        TransactionTemplate transactionTemplate) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Handler cho ResourceReservedEvent — trừ available trong DB.
     */
    public EventHandler<ResourceReservedEvent> reservedHandler() {
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
    public EventHandler<ResourceReleasedEvent> releasedHandler() {
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
     *
     * @param eventId   ID event dùng làm dedup key
     * @param eventType loại event (cho logging)
     * @param action    business logic cần thực hiện
     * @return true nếu event được xử lý, false nếu duplicate (đã xử lý trước đó)
     */
    private boolean processIdempotent(String eventId, String eventType, Runnable action) {
        try {
            transactionTemplate.execute(status -> {
                // INSERT dedup record — nếu trùng → DataIntegrityViolationException
                processedEventRepository.save(new ProcessedEvent(eventId, eventType));

                // Business logic — chỉ chạy nếu INSERT thành công
                action.run();

                return null;
            });
            return true;
        } catch (DataIntegrityViolationException e) {
            // eventId đã tồn tại → event đã được xử lý trước đó → skip
            return false;
        }
    }
}
