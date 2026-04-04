package io.hrc.eventbus.adapter.rabbitmq;

import io.hrc.core.domain.DomainEvent;
import io.hrc.eventbus.Acknowledgment;
import io.hrc.eventbus.EventBusCapabilities;
import io.hrc.eventbus.EventDestination;
import io.hrc.eventbus.adapter.AbstractEventBusAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Adapter cho RabbitMQ. Dùng Topic Exchange để routing linh hoạt.
 *
 * <p><b>Topology:</b>
 * <pre>
 * Exchange: hcr.events (type: topic)
 *   ├── Binding: routing-key="order-created" → Queue: hcr.order-created.queue
 *   ├── Binding: routing-key="payment-*"    → Queue: hcr.payment.queue
 *   └── ...
 * </pre>
 *
 * <p><b>publishIdempotent():</b> RabbitMQ không có native idempotent producer
 * → Framework check {@code eventId} trong Redis trước khi publish.
 * Nếu {@code eventId} đã tồn tại trong Redis → skip publish.
 * Redis key TTL = 24 giờ (configurable).
 *
 * <p><b>Ack mode:</b> Manual — consumer xác nhận sau khi xử lý xong.
 * Framework set {@code AcknowledgeMode.MANUAL} trong listener container config.
 *
 * <p><b>Dependency:</b> Cần {@code spring-boot-starter-amqp} và kết nối Redis
 * (cho idempotent publish check).
 */
@Slf4j
public class RabbitMQEventBusAdapter extends AbstractEventBusAdapter {

    private static final String EXCHANGE_NAME  = "hcr.events";
    private static final String QUEUE_PREFIX   = "hcr.";
    private static final String QUEUE_SUFFIX   = ".queue";
    private static final String IDEMPOTENT_KEY_PREFIX = "hcr:mq:published:";
    private static final Duration IDEMPOTENT_TTL = Duration.ofHours(24);

    private final RabbitTemplate rabbitTemplate;
    private final StringRedisTemplate redisTemplate;

    public RabbitMQEventBusAdapter(RabbitTemplate rabbitTemplate,
                                    StringRedisTemplate redisTemplate) {
        this.rabbitTemplate = rabbitTemplate;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void publish(DomainEvent event) {
        EventDestination destination = EventDestination.forEventType(event.getClass());
        publish(event, destination);
    }

    @Override
    public void publish(DomainEvent event, EventDestination destination) {
        String routingKey = destination.getName();
        String payload    = serializeEvent(event);

        log.debug("[RabbitMQ] Publishing: exchange={}, routingKey={}, eventId={}",
            EXCHANGE_NAME, routingKey, event.getEventId());

        rabbitTemplate.convertAndSend(EXCHANGE_NAME, routingKey, payload);

        log.debug("[RabbitMQ] Published OK: eventId={}", event.getEventId());
    }

    @Override
    public void publishIdempotent(DomainEvent event, String idempotencyKey) {
        // RabbitMQ không có native idempotent producer → check Redis
        String redisKey = IDEMPOTENT_KEY_PREFIX + idempotencyKey;

        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", IDEMPOTENT_TTL);
        if (Boolean.FALSE.equals(isNew)) {
            log.debug("[RabbitMQ] Skipping duplicate publish: idempotencyKey={}", idempotencyKey);
            return;
        }

        publish(event);
    }

    @Override
    public EventBusCapabilities getCapabilities() {
        return EventBusCapabilities.rabbitMQ();
    }

    // =========================================================================
    // RabbitMQ consumer callback — được gọi bởi @RabbitListener trong Spring
    // =========================================================================

    /**
     * Nhận message từ RabbitMQ và dispatch đến handlers đã đăng ký.
     * Được gọi bởi {@code @RabbitListener} annotation trong Spring AMQP.
     *
     * @param payload   JSON payload của event
     * @param eventType simple class name của event
     * @param channel   AMQP Channel (dùng cho manual ack)
     * @param tag       delivery tag (dùng cho basicAck/basicNack)
     */
    public void onRabbitMessage(String payload, String eventType,
                                 com.rabbitmq.client.Channel channel, long tag) {
        log.debug("[RabbitMQ] Received: eventType={}", eventType);

        DomainEvent event = deserializeEvent(payload, eventType);
        if (event == null) {
            log.error("[RabbitMQ] Failed to deserialize: eventType={}", eventType);
            ackSilently(channel, tag, false); // dead letter bad message
            return;
        }

        Acknowledgment ack = new RabbitAcknowledgment(channel, tag, event);
        dispatch(event, ack);
    }

    private void ackSilently(com.rabbitmq.client.Channel channel, long tag, boolean requeue) {
        try {
            channel.basicNack(tag, false, requeue);
        } catch (Exception e) {
            log.error("[RabbitMQ] Failed to nack: {}", e.getMessage());
        }
    }

    // =========================================================================
    // Serialization (placeholder)
    // =========================================================================

    private String serializeEvent(DomainEvent event) {
        return String.format("{\"eventId\":\"%s\",\"eventType\":\"%s\",\"correlationId\":\"%s\"}",
            event.getEventId(), event.getEventType(), event.getCorrelationId());
    }

    private DomainEvent deserializeEvent(String payload, String eventType) {
        log.debug("[RabbitMQ] Deserializing: eventType={}", eventType);
        return null; // Production: dùng ObjectMapper với class registry
    }

    // =========================================================================
    // Inner class: RabbitAcknowledgment
    // =========================================================================

    private static class RabbitAcknowledgment implements Acknowledgment {

        private final com.rabbitmq.client.Channel channel;
        private final long deliveryTag;
        private final DomainEvent event;

        RabbitAcknowledgment(com.rabbitmq.client.Channel channel, long deliveryTag,
                              DomainEvent event) {
            this.channel     = channel;
            this.deliveryTag = deliveryTag;
            this.event       = event;
        }

        @Override
        public void acknowledge() {
            try {
                channel.basicAck(deliveryTag, false);
                log.debug("[RabbitMQ-Ack] Acknowledged: eventId={}", event.getEventId());
            } catch (Exception e) {
                log.error("[RabbitMQ-Ack] basicAck failed: eventId={}, error={}",
                    event.getEventId(), e.getMessage());
            }
        }

        @Override
        public void reject() {
            reject(true);
        }

        @Override
        public void reject(boolean requeue) {
            try {
                channel.basicNack(deliveryTag, false, requeue);
                if (requeue) {
                    event.incrementRetryCount();
                    log.warn("[RabbitMQ-Ack] Rejected (requeue): eventId={}, retryCount={}",
                        event.getEventId(), event.getRetryCount());
                } else {
                    log.warn("[RabbitMQ-Ack] Rejected (dead letter): eventId={}", event.getEventId());
                }
            } catch (Exception e) {
                log.error("[RabbitMQ-Ack] basicNack failed: eventId={}, error={}",
                    event.getEventId(), e.getMessage());
            }
        }
    }
}
