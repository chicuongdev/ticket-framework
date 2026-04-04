package io.hrc.eventbus;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/**
 * Abstraction cho topic/queue/exchange — không dùng tên cụ thể của từng message broker.
 * Framework map {@code EventDestination} sang cấu trúc phù hợp với từng adapter.
 *
 * <p><b>Mapping theo adapter:</b>
 * <pre>
 * EventDestination.name  Kafka             RabbitMQ        Redis Streams
 * "order-created"        hcr.order-created order-created   hcr:stream:order-created
 * </pre>
 *
 * <p>Prefix "hcr." (Kafka/Redis) và exchange name (RabbitMQ) được configure
 * qua {@code hcr.event-bus.*} properties.
 *
 * <p><b>Cách tạo:</b>
 * <pre>{@code
 * // Tên cụ thể
 * EventDestination dest = EventDestination.of("order-created");
 *
 * // Auto-derive từ event class (convention: class name → kebab-case)
 * EventDestination dest = EventDestination.forEventType(OrderCreatedEvent.class);
 * // → "order-created"
 * }</pre>
 */
@Getter
public class EventDestination {

    private final String name;

    private EventDestination(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("EventDestination name must not be blank");
        }
        this.name = name;
    }

    /**
     * Tạo destination với tên cụ thể.
     *
     * @param name tên destination (không được null hoặc blank)
     * @return EventDestination instance
     */
    public static EventDestination of(String name) {
        return new EventDestination(name);
    }

    /**
     * Tạo destination từ tên class của event theo convention:
     * {@code OrderCreatedEvent} → {@code "order-created"}.
     *
     * <p>Convention: class simple name, bỏ suffix "Event", chuyển CamelCase → kebab-case.
     *
     * @param eventClass class của event
     * @return EventDestination với tên được derive từ class name
     */
    public static EventDestination forEventType(Class<? extends DomainEvent> eventClass) {
        String simpleName = eventClass.getSimpleName();
        // Bỏ suffix "Event" nếu có
        if (simpleName.endsWith("Event")) {
            simpleName = simpleName.substring(0, simpleName.length() - 5);
        }
        // CamelCase → kebab-case
        String kebab = simpleName
            .replaceAll("([A-Z])", "-$1")
            .toLowerCase()
            .replaceFirst("^-", "");
        return new EventDestination(kebab);
    }

    @Override
    public String toString() {
        return "EventDestination{name='" + name + "'}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EventDestination)) return false;
        return name.equals(((EventDestination) o).name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }
}
