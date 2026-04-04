package io.hrc.eventbus;

import lombok.Builder;
import lombok.Getter;

/**
 * Khai báo tường minh những tính năng mà mỗi adapter hỗ trợ.
 *
 * <p>Framework dùng để warn developer khi họ rely vào tính năng không được support
 * bởi adapter đang config. Warning được log khi startup (không throw exception,
 * không block startup).
 *
 * <p><b>Capabilities theo adapter:</b>
 * <pre>
 * Capability            Kafka  RabbitMQ  Redis Streams  InMemory
 * supportsOrdering        ✓       ✓           ✓            ✓
 * supportsReplay          ✓       ✗           ✓            ✗
 * supportsExactlyOnce     ✓       ✗           ✗            ✓
 * supportsPartitioning    ✓       ✗           ✗            ✗
 * isSynchronous           ✗       ✗           ✗            ✓
 * supportsDLQ             ✓       ✓         Limited        ✓
 * supportsMultiConsumer   ✓       ✓           ✓            ✗
 * </pre>
 *
 * <p><b>Cách sử dụng tại runtime:</b>
 * <pre>{@code
 * EventBusCapabilities cap = eventBus.getCapabilities();
 * if (cap.isSupportsReplay()) {
 *     // safe to replay events
 * } else {
 *     // warn: replay not supported by this adapter
 * }
 * }</pre>
 */
@Getter
@Builder
public class EventBusCapabilities {

    /**
     * Đảm bảo thứ tự message trong cùng partition/queue/stream.
     * Không đảm bảo ordering cross-partition.
     */
    private final boolean supportsOrdering;

    /**
     * Có thể đọc lại message cũ (seek/replay).
     * Kafka: XRANGE / seek; Redis Streams: XRANGE từ offset.
     * RabbitMQ và InMemory: không hỗ trợ.
     */
    private final boolean supportsReplay;

    /**
     * Đảm bảo message được xử lý đúng 1 lần.
     * Kafka: idempotent producer. InMemory: synchronous delivery.
     * RabbitMQ và Redis Streams: at-least-once, cần consumer idempotency.
     */
    private final boolean supportsExactlyOnce;

    /**
     * Partition message theo key (ví dụ: resourceId → cùng partition).
     * Chỉ Kafka hỗ trợ native.
     */
    private final boolean supportsPartitioning;

    /**
     * Deliver event synchronously trong cùng thread của publisher.
     * Chỉ InMemoryEventBusAdapter hỗ trợ — các adapter production đều async.
     */
    private final boolean isSynchronous;

    /**
     * Có Dead Letter Queue để chứa event fail quá nhiều lần.
     * Redis Streams hỗ trợ hạn chế (cần config thêm).
     */
    private final boolean supportsDLQ;

    /**
     * Nhiều consumer instance có thể consume song song.
     * InMemory không hỗ trợ vì synchronous single-threaded.
     */
    private final boolean supportsMultiConsumer;

    // =========================================================================
    // Static factory methods cho từng adapter
    // =========================================================================

    /**
     * Capabilities của KafkaEventBusAdapter.
     * Hỗ trợ đầy đủ nhất — default cho production high-load.
     */
    public static EventBusCapabilities kafka() {
        return EventBusCapabilities.builder()
            .supportsOrdering(true)
            .supportsReplay(true)
            .supportsExactlyOnce(true)
            .supportsPartitioning(true)
            .isSynchronous(false)
            .supportsDLQ(true)
            .supportsMultiConsumer(true)
            .build();
    }

    /**
     * Capabilities của RabbitMQEventBusAdapter.
     * Không hỗ trợ replay và exactly-once.
     */
    public static EventBusCapabilities rabbitMQ() {
        return EventBusCapabilities.builder()
            .supportsOrdering(true)
            .supportsReplay(false)
            .supportsExactlyOnce(false)
            .supportsPartitioning(false)
            .isSynchronous(false)
            .supportsDLQ(true)
            .supportsMultiConsumer(true)
            .build();
    }

    /**
     * Capabilities của RedisStreamEventBusAdapter.
     * Tốt khi đã có Redis, không muốn thêm Kafka/RabbitMQ.
     */
    public static EventBusCapabilities redisStream() {
        return EventBusCapabilities.builder()
            .supportsOrdering(true)
            .supportsReplay(true)
            .supportsExactlyOnce(false)
            .supportsPartitioning(false)
            .isSynchronous(false)
            .supportsDLQ(false)   // hạn chế, cần config thêm
            .supportsMultiConsumer(true)
            .build();
    }

    /**
     * Capabilities của InMemoryEventBusAdapter.
     * Chỉ dùng cho unit test — không dùng production.
     */
    public static EventBusCapabilities inMemory() {
        return EventBusCapabilities.builder()
            .supportsOrdering(true)
            .supportsReplay(false)
            .supportsExactlyOnce(true)
            .supportsPartitioning(false)
            .isSynchronous(true)
            .supportsDLQ(true)   // in-memory dead letter list
            .supportsMultiConsumer(false)
            .build();
    }
}
