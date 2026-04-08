package io.hrc.inventory.persistence;

import io.hrc.eventbus.EventHandler;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.event.ResourceReleasedEvent;
import io.hrc.inventory.event.ResourceReservedEvent;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Batch consumer — gom nhiều events cùng resourceId rồi flush 1 lần.
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
public class BatchInventoryPersistenceConsumer {

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final ProcessedEventRepository processedEventRepository;
    private final TransactionTemplate transactionTemplate;
    private final PersistenceConfig config;

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

    public BatchInventoryPersistenceConsumer(EntityManager entityManager,
                                             Class<? extends AbstractInventoryEntity> entityClass,
                                             ProcessedEventRepository processedEventRepository,
                                             TransactionTemplate transactionTemplate,
                                             PersistenceConfig config) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.processedEventRepository = processedEventRepository;
        this.transactionTemplate = transactionTemplate;
        this.config = config;

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
    // EventHandler factories — tương tự InventoryPersistenceConsumer
    // =========================================================================

    public EventHandler<ResourceReservedEvent> reservedHandler() {
        return (event, ack) -> {
            bufferEvent(event.getResourceId(), event.getEventId(),
                "ResourceReservedEvent", -event.getQuantity());
            ack.acknowledge();
        };
    }

    public EventHandler<ResourceReleasedEvent> releasedHandler() {
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
     *
     * <p>Logic:
     * <ol>
     *   <li>Tính tổng delta = sum(event.delta)</li>
     *   <li>BEGIN TRANSACTION</li>
     *   <li>INSERT tất cả eventIds vào hcr_processed_events</li>
     *   <li>UPDATE available = available + totalDelta WHERE resource_id = ?</li>
     *   <li>COMMIT</li>
     * </ol>
     *
     * <p>Nếu batch INSERT fail (duplicate eventId) → fallback xử lý từng event một
     * để xác định event nào duplicate, event nào cần xử lý.
     */
    private void doFlush(String resourceId, List<BufferedEvent> events) {
        try {
            transactionTemplate.execute(status -> {
                // INSERT tất cả dedup records
                for (BufferedEvent e : events) {
                    processedEventRepository.save(new ProcessedEvent(e.eventId, e.eventType));
                }
                // Flush INSERT trước để detect duplicate sớm
                entityManager.flush();

                // Tính tổng delta và UPDATE 1 lần
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
            // Batch có ít nhất 1 duplicate eventId → fallback từng event
            log.warn("[P3-Batch] Duplicate detected in batch for resourceId={}, " +
                "falling back to single-event processing ({} events)", resourceId, events.size());
            fallbackSingleProcess(resourceId, events);
        }
    }

    /**
     * Fallback: xử lý từng event một khi batch fail do duplicate.
     * Event duplicate sẽ bị skip, event mới sẽ được xử lý bình thường.
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
     * Shutdown gracefully — flush remaining buffer rồi tắt scheduler.
     */
    public void shutdown() {
        log.info("[P3-Batch] Shutting down — flushing remaining buffer...");
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
        final int delta; // âm = reserve, dương = release

        BufferedEvent(String eventId, String eventType, int delta) {
            this.eventId = eventId;
            this.eventType = eventType;
            this.delta = delta;
        }
    }
}
