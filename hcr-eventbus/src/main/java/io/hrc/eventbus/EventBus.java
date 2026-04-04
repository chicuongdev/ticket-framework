package io.hrc.eventbus;

import io.hrc.core.domain.DomainEvent;

import java.util.List;

/**
 * Contract trung tâm cho pub/sub messaging trong framework.
 *
 * <p><b>Delivery guarantee:</b> At-least-once — consumer có thể nhận cùng event
 * nhiều hơn 1 lần, nên mọi {@link EventHandler} PHẢI implement idempotency.
 *
 * <p><b>Sử dụng:</b>
 * <pre>{@code
 * // Publish
 * eventBus.publish(new OrderCreatedEvent(resourceId, orderId, correlationId));
 *
 * // Publish với destination cụ thể
 * eventBus.publish(event, EventDestination.of("order-priority"));
 *
 * // Subscribe
 * eventBus.subscribe(OrderCreatedEvent.class, myHandler);
 * }</pre>
 *
 * <p><b>Routing mặc định:</b> Framework tự map event class → destination
 * qua {@link EventDestination#forEventType(Class)}.
 */
public interface EventBus {

    /**
     * Publish event lên destination mặc định.
     * Destination được tự động detect từ event type.
     *
     * @param event event cần publish (không được null)
     */
    void publish(DomainEvent event);

    /**
     * Publish event lên destination cụ thể, override default routing.
     *
     * @param event       event cần publish
     * @param destination destination muốn publish tới
     */
    void publish(DomainEvent event, EventDestination destination);

    /**
     * Publish có idempotency: đảm bảo không publish 2 lần cùng idempotency key.
     * Dùng khi producer có thể retry (ví dụ: network timeout).
     *
     * @param event          event cần publish
     * @param idempotencyKey key để check duplicate
     */
    void publishIdempotent(DomainEvent event, String idempotencyKey);

    /**
     * Publish nhiều event cùng lúc — hiệu quả hơn publish từng cái một.
     * Framework đảm bảo tất cả events được deliver (không partial publish).
     *
     * @param events danh sách events cần publish
     */
    void publishBatch(List<? extends DomainEvent> events);

    /**
     * Đăng ký handler để nhận event theo loại.
     * Có thể đăng ký nhiều handler cho cùng một eventType.
     *
     * @param eventType class của event cần subscribe
     * @param handler   handler xử lý event
     * @param <T>       type của event
     */
    <T extends DomainEvent> void subscribe(Class<T> eventType, EventHandler<T> handler);

    /**
     * Hủy đăng ký handler đã subscribe trước đó.
     *
     * @param eventType class của event
     * @param handler   handler cần hủy
     * @param <T>       type của event
     */
    <T extends DomainEvent> void unsubscribe(Class<T> eventType, EventHandler<T> handler);

    /**
     * Lấy capabilities của adapter đang dùng.
     * Dùng để viết conditional logic mà không hardcode tên adapter.
     *
     * <pre>{@code
     * if (eventBus.getCapabilities().isSupportsReplay()) {
     *     // safe to replay
     * }
     * }</pre>
     *
     * @return capabilities object của adapter hiện tại
     */
    EventBusCapabilities getCapabilities();
}
