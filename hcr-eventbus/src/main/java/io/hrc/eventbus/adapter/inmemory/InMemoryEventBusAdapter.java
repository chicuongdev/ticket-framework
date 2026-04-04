package io.hrc.eventbus.adapter.inmemory;

import io.hrc.core.domain.DomainEvent;
import io.hrc.eventbus.Acknowledgment;
import io.hrc.eventbus.EventBusCapabilities;
import io.hrc.eventbus.EventDestination;
import io.hrc.eventbus.adapter.AbstractEventBusAdapter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Adapter hoàn toàn in-memory — <b>KHÔNG DÙNG CHO PRODUCTION</b>.
 *
 * <p><b>Đặc điểm:</b>
 * <ul>
 *   <li><b>Synchronous delivery:</b> Handler được gọi ngay trong thread của publisher.</li>
 *   <li><b>No persistence:</b> Restart ứng dụng → mất toàn bộ events.</li>
 *   <li><b>Thread-safe:</b> Dùng {@code ConcurrentHashMap} và {@code CopyOnWriteArrayList}.</li>
 *   <li><b>No infrastructure:</b> Không cần Kafka, RabbitMQ, Redis.</li>
 * </ul>
 *
 * <p><b>Testing utilities:</b> Xem các method {@code getPublishedEvents()},
 * {@code clearEvents()}, {@code getPublishedCount()} — dùng để assert trong test.
 *
 * <p><b>CẢNH BÁO:</b> Test với InMemoryEventBusAdapter (synchronous, exactly-once)
 * rồi deploy KafkaEventBusAdapter (asynchronous, at-least-once) → behavior khác nhau
 * hoàn toàn ở production. Môi trường integration test nên dùng cùng adapter với production.
 */
@Slf4j
public class InMemoryEventBusAdapter extends AbstractEventBusAdapter {

    /** Lưu tất cả events đã được publish — dùng cho testing assertions. */
    private final List<DomainEvent> publishedEvents = new CopyOnWriteArrayList<>();

    /** Dead letter storage — events fail quá retry limit. */
    private final Map<String, List<DomainEvent>> deadLetterMap = new ConcurrentHashMap<>();

    @Override
    public void publish(DomainEvent event) {
        publish(event, EventDestination.forEventType(event.getClass()));
    }

    @Override
    public void publish(DomainEvent event, EventDestination destination) {
        publishedEvents.add(event);
        log.debug("[InMemory] Publishing: type={}, eventId={}, destination={}",
            event.getEventType(), event.getEventId(), destination.getName());

        // Synchronous delivery — gọi handlers ngay lập tức
        Acknowledgment ack = new InMemoryAcknowledgment(event, this);
        dispatch(event, ack);
    }

    @Override
    public void publishIdempotent(DomainEvent event, String idempotencyKey) {
        // InMemory: exactly-once vì synchronous, không cần Redis check
        // Đơn giản là check xem event với cùng eventId đã publish chưa
        boolean alreadyPublished = publishedEvents.stream()
            .anyMatch(e -> e.getEventId().equals(event.getEventId()));

        if (alreadyPublished) {
            log.debug("[InMemory] Skipping duplicate publish: eventId={}, idempotencyKey={}",
                event.getEventId(), idempotencyKey);
            return;
        }
        publish(event);
    }

    @Override
    public EventBusCapabilities getCapabilities() {
        return EventBusCapabilities.inMemory();
    }

    // =========================================================================
    // Testing utilities — dùng trong test code, không dùng trong production code
    // =========================================================================

    /**
     * Lấy tất cả events đã được publish kể từ khi khởi tạo hoặc sau lần {@link #clearEvents()} gần nhất.
     *
     * @return list events đã publish (unmodifiable)
     */
    public List<DomainEvent> getPublishedEvents() {
        return List.copyOf(publishedEvents);
    }

    /**
     * Lấy events đã publish theo loại (type-safe).
     *
     * <pre>{@code
     * List<OrderCreatedEvent> events = bus.getPublishedEvents(OrderCreatedEvent.class);
     * assertThat(events).hasSize(1);
     * }</pre>
     *
     * @param eventType class của event cần lọc
     * @param <T>       type của event
     * @return list events đã publish thuộc type này
     */
    public <T extends DomainEvent> List<T> getPublishedEvents(Class<T> eventType) {
        return publishedEvents.stream()
            .filter(eventType::isInstance)
            .map(eventType::cast)
            .collect(Collectors.toList());
    }

    /**
     * Xóa tất cả published events — dùng giữa các test case để reset state.
     */
    public void clearEvents() {
        publishedEvents.clear();
        deadLetterMap.clear();
        log.debug("[InMemory] Events cleared.");
    }

    /**
     * Đếm số events đã publish thuộc loại cụ thể.
     *
     * @param eventType class của event cần đếm
     * @return số lượng events thuộc type này
     */
    public long getPublishedCount(Class<? extends DomainEvent> eventType) {
        return publishedEvents.stream()
            .filter(eventType::isInstance)
            .count();
    }

    /**
     * Lấy dead letter events (fail quá retry limit) của một loại event.
     *
     * @param eventType class của event
     * @return list events trong dead letter
     */
    public List<DomainEvent> getDeadLetterEvents(Class<? extends DomainEvent> eventType) {
        return deadLetterMap.getOrDefault(eventType.getSimpleName(), List.of());
    }

    // =========================================================================
    // Internal
    // =========================================================================

    void addToDeadLetter(DomainEvent event) {
        String key = event.getClass().getSimpleName();
        deadLetterMap.computeIfAbsent(key, k -> new ArrayList<>()).add(event);
        log.warn("[InMemory] Event moved to dead letter: type={}, eventId={}",
            event.getEventType(), event.getEventId());
    }

    // =========================================================================
    // Inner class: InMemoryAcknowledgment
    // =========================================================================

    private static class InMemoryAcknowledgment implements Acknowledgment {

        private final DomainEvent event;
        private final InMemoryEventBusAdapter adapter;

        InMemoryAcknowledgment(DomainEvent event, InMemoryEventBusAdapter adapter) {
            this.event = event;
            this.adapter = adapter;
        }

        @Override
        public void acknowledge() {
            // No-op: synchronous delivery đã xong khi handler return
            log.debug("[InMemory-Ack] Acknowledged: eventId={}", event.getEventId());
        }

        @Override
        public void reject() {
            reject(true);
        }

        @Override
        public void reject(boolean requeue) {
            if (requeue) {
                event.incrementRetryCount();
                log.debug("[InMemory-Ack] Rejected (retry): eventId={}, retryCount={}",
                    event.getEventId(), event.getRetryCount());
                // InMemory: không thực sự retry async, chỉ log (test environment)
            } else {
                adapter.addToDeadLetter(event);
            }
        }
    }
}
