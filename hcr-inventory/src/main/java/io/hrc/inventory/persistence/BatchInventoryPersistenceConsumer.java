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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Batch consumer — gom nhiều events cùng resourceId rồi flush 1 lần.
 *
 * <p><b>Tự động subscribe/unsubscribe</b> qua Spring lifecycle hooks
 * ({@link InitializingBean} + {@link DisposableBean}). {@code destroy()} flush
 * remaining buffer rồi shutdown scheduler — tránh data loss khi app restart.
 *
 * <p><b>Vấn đề SINGLE mode giải quyết không tốt:</b>
 * 10,000 reserve/s → 10,000 transaction/s (mỗi event = 1 INSERT dedup + 1 UPDATE available).
 * PostgreSQL xử lý ~5,000-10,000 simple tx/s → bottleneck khi tải cao.
 *
 * <p><b>Batch mode:</b>
 * <pre>
 * Thay vì:
 *   tx1: UPDATE available = 100-1 WHERE id='A'   (event-1)
 *   tx2: UPDATE available =  99-1 WHERE id='A'   (event-2)
 *   ...
 *   tx100: UPDATE available = 1-1 WHERE id='A'   (event-100)
 *   = 100 transactions
 *
 * Gom lại:
 *   tx1: UPDATE available = available - 100 WHERE id='A'
 *        + INSERT 100 rows vào hcr_processed_events
 *   = 1 transaction
 * </pre>
 *
 * <p><b>Flush triggers:</b>
 * <ol>
 *   <li>Buffer đạt {@code batchSize} events cho 1 resourceId → flush resourceId đó.</li>
 *   <li>Scheduler chạy mỗi {@code flushIntervalMs} → flush ALL buffered resources.</li>
 * </ol>
 *
 * <p><b>Idempotency:</b> Vẫn INSERT eventId vào {@code hcr_processed_events} trong cùng
 * transaction. Nếu bất kỳ eventId nào trùng → transaction rollback → retry từng event
 * một (fallback sang single mode) để xác định event nào duplicate.
 *
 * <p><b>Trade-off:</b> DB lag tăng thêm tối đa {@code flushIntervalMs} (default 1s).
 * Chấp nhận được vì P3 đã cam kết eventual consistency.
 *
 * <p><b>Cấu hình:</b>
 * <pre>
 * hcr.inventory.persistence.mode=batch
 * hcr.inventory.persistence.batch-size=500
 * hcr.inventory.persistence.flush-interval-ms=1000
 * </pre>
 */
@Slf4j
public class BatchInventoryPersistenceConsumer implements InitializingBean, DisposableBean {

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final PersistenceConfig config;
    private final EventBus eventBus;

    /**
     * Buffer gom events theo resourceId.
     * Key = resourceId, Value = danh sách event đang chờ flush.
     */
    private final ConcurrentHashMap<String, List<BufferedEvent>> buffer = new ConcurrentHashMap<>();

    /**
     * Lock per resourceId — đảm bảo flush và buffer add không race.
     */
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    private EventHandler<ResourceReservedEvent> reservedHandler;
    private EventHandler<ResourceReleasedEvent> releasedHandler;

    public BatchInventoryPersistenceConsumer(EntityManager entityManager,
                                             Class<? extends AbstractInventoryEntity> entityClass,
                                             ProcessedEventRepository processedEventRepository,
                                             TransactionTemplate transactionTemplate,
                                             PersistenceConfig config,
                                             EventBus eventBus) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.config = config;
        this.eventBus = eventBus;

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hcr-batch-flush");
            t.setDaemon(true);
            return t;
        });

        this.scheduler.scheduleWithFixedDelay(
            this::flushAll,
            config.getFlushIntervalMs(),
            config.getFlushIntervalMs(),
            TimeUnit.MILLISECONDS
        );

        log.info("[P3-Batch] Started with batchSize={}, flushIntervalMs={}",
            config.getBatchSize(), config.getFlushIntervalMs());
    }

    // =========================================================================
    // Spring lifecycle — auto subscribe/unsubscribe + graceful shutdown
    // =========================================================================

    @Override
    public void afterPropertiesSet() {
        this.reservedHandler = buildReservedHandler();
        this.releasedHandler = buildReleasedHandler();
        eventBus.subscribe(ResourceReservedEvent.class, reservedHandler);
        eventBus.subscribe(ResourceReleasedEvent.class, releasedHandler);
        log.info("[P3-Batch] Subscribed to ResourceReservedEvent + ResourceReleasedEvent");
    }

    /**
     * Graceful shutdown — unsubscribe, flush remaining buffer, rồi tắt scheduler.
     * Spring sẽ gọi tự động khi context close.
     */
    @Override
    public void destroy() {
        log.info("[P3-Batch] Shutting down — unsubscribing and flushing remaining buffer...");

        if (reservedHandler != null) {
            eventBus.unsubscribe(ResourceReservedEvent.class, reservedHandler);
        }
        if (releasedHandler != null) {
            eventBus.unsubscribe(ResourceReleasedEvent.class, releasedHandler);
        }

        scheduler.shutdown();
        flushAll();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[P3-Batch] Shutdown complete.");
    }

    // =========================================================================
    // EventHandler factories
    // =========================================================================

    public EventHandler<ResourceReservedEvent> buildReservedHandler() {
        return (event, ack) -> {
            bufferEvent(event.getResourceId(), event.getEventId(),
                "ResourceReservedEvent", -event.getQuantity());
            ack.acknowledge();
        };
    }

    public EventHandler<ResourceReleasedEvent> buildReleasedHandler() {
        return (event, ack) -> {
            bufferEvent(event.getResourceId(), event.getEventId(),
                "ResourceReleasedEvent", event.getQuantity());
            ack.acknowledge();
        };
    }

    // =========================================================================
    // Buffer management
    // =========================================================================

    private void bufferEvent(String resourceId, String eventId, String eventType, int delta) {
        ReentrantLock lock = locks.computeIfAbsent(resourceId, k -> new ReentrantLock());
        lock.lock();
        try {
            List<BufferedEvent> events = buffer.computeIfAbsent(resourceId, k -> new ArrayList<>());
            events.add(new BufferedEvent(eventId, eventType, delta));

            if (events.size() >= config.getBatchSize()) {
                log.debug("[P3-Batch] Buffer full for resourceId={}, flushing {} events",
                    resourceId, events.size());
                doFlush(resourceId, drainBuffer(resourceId));
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drain buffer cho 1 resourceId — trả về events và clear buffer.
     * PHẢI gọi trong lock.
     */
    private List<BufferedEvent> drainBuffer(String resourceId) {
        List<BufferedEvent> events = buffer.remove(resourceId);
        return events != null ? events : List.of();
    }

    // =========================================================================
    // Flush logic
    // =========================================================================

    /**
     * Flush tất cả resourceIds đang có events trong buffer.
     * Được gọi bởi scheduler mỗi flushIntervalMs.
     */
    void flushAll() {
        for (String resourceId : buffer.keySet()) {
            ReentrantLock lock = locks.computeIfAbsent(resourceId, k -> new ReentrantLock());
            lock.lock();
            try {
                List<BufferedEvent> events = drainBuffer(resourceId);
                if (!events.isEmpty()) {
                    doFlush(resourceId, events);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * Flush 1 batch events cho 1 resourceId trong 1 transaction.
     */
    private void doFlush(String resourceId, List<BufferedEvent> events) {
        try {
            transactionTemplate.execute(status -> {
                for (BufferedEvent e : events) {
                    processedEventRepository.save(new ProcessedEvent(e.eventId, e.eventType));
                }
                entityManager.flush();

                int totalDelta = events.stream().mapToInt(e -> e.delta).sum();

                AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
                if (entity == null) {
                    log.warn("[P3-Batch] Resource not found in DB: {}", resourceId);
                    return null;
                }

                entity.setAvailable(entity.getAvailable() + totalDelta);
                entity.setUpdatedAt(Instant.now());
                entityManager.merge(entity);

                return null;
            });

            log.debug("[P3-Batch] Flushed {} events for resourceId={}, OK", events.size(), resourceId);

        } catch (DataIntegrityViolationException e) {
            log.warn("[P3-Batch] Duplicate detected in batch for resourceId={}, " +
                "falling back to single-event processing ({} events)", resourceId, events.size());
            fallbackSingleProcess(resourceId, events);
        }
    }

    /**
     * Fallback: xử lý từng event một khi batch fail do duplicate.
     */
    private void fallbackSingleProcess(String resourceId, List<BufferedEvent> events) {
        for (BufferedEvent event : events) {
            try {
                transactionTemplate.execute(status -> {
                    processedEventRepository.save(new ProcessedEvent(event.eventId, event.eventType));

                    AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
                    if (entity == null) {
                        log.warn("[P3-Batch] Resource not found in DB: {}", resourceId);
                        return null;
                    }

                    entity.setAvailable(entity.getAvailable() + event.delta);
                    entity.setUpdatedAt(Instant.now());
                    entityManager.merge(entity);

                    return null;
                });
            } catch (DataIntegrityViolationException ex) {
                log.debug("[P3-Batch] Duplicate event skipped: eventId={}", event.eventId);
            }
        }
    }

    /**
     * Số events đang chờ trong buffer (cho monitoring/testing).
     */
    public int getPendingCount() {
        return buffer.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Số events đang chờ cho 1 resourceId cụ thể (cho monitoring/testing).
     */
    public int getPendingCount(String resourceId) {
        List<BufferedEvent> events = buffer.get(resourceId);
        return events != null ? events.size() : 0;
    }

    // =========================================================================
    // Inner class
    // =========================================================================

    private static class BufferedEvent {
        final String eventId;
        final String eventType;
        final int delta;

        BufferedEvent(String eventId, String eventType, int delta) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.delta = delta;
        }
    }
}
