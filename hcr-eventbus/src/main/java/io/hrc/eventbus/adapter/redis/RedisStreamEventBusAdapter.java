package io.hrc.eventbus.adapter.redis;

import io.hrc.core.domain.DomainEvent;
import io.hrc.eventbus.Acknowledgment;
import io.hrc.eventbus.EventBusCapabilities;
import io.hrc.eventbus.EventDestination;
import io.hrc.eventbus.adapter.AbstractEventBusAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Map;

/**
 * Adapter dùng Redis Streams (XADD/XREADGROUP).
 * Phù hợp khi đã có Redis infrastructure và không muốn thêm Kafka hoặc RabbitMQ.
 *
 * <p><b>Cơ chế:</b>
 * <pre>
 * publish():
 *   XADD hcr:stream:{destination} * eventId {uuid} eventType {type} payload {json}
 *
 * subscribe() / consume():
 *   XREADGROUP GROUP hcr-consumers consumer-{n}
 *   COUNT 10 BLOCK 2000
 *   STREAMS hcr:stream:{destination} >
 *
 * acknowledge():
 *   XACK hcr:stream:{destination} hcr-consumers {messageId}
 * </pre>
 *
 * <p><b>publishIdempotent():</b> Check {@code eventId} trong Redis SET trước XADD.
 * Dùng {@code SETNX} với TTL 24 giờ.
 *
 * <p><b>Consumer group:</b> Framework tự tạo consumer group nếu chưa tồn tại
 * {@code XGROUP CREATE ... MKSTREAM}.
 *
 * <p><b>Giới hạn:</b> DLQ support hạn chế — cần config XAUTOCLAIM hoặc external job
 * để xử lý PEL (Pending Entry List) sau timeout.
 */
@Slf4j
public class RedisStreamEventBusAdapter extends AbstractEventBusAdapter {

    private static final String STREAM_PREFIX     = "hcr:stream:";
    private static final String CONSUMER_GROUP    = "hcr-consumers";
    private static final String IDEMPOTENT_PREFIX = "hcr:stream:published:";
    private static final Duration IDEMPOTENT_TTL  = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;
    private final String consumerName;

    public RedisStreamEventBusAdapter(StringRedisTemplate redisTemplate) {
        this(redisTemplate, "consumer-" + ProcessHandle.current().pid());
    }

    public RedisStreamEventBusAdapter(StringRedisTemplate redisTemplate, String consumerName) {
        this.redisTemplate  = redisTemplate;
        this.consumerName   = consumerName;
    }

    @Override
    public void publish(DomainEvent event) {
        EventDestination destination = EventDestination.forEventType(event.getClass());
        publish(event, destination);
    }

    @Override
    public void publish(DomainEvent event, EventDestination destination) {
        String streamKey = STREAM_PREFIX + destination.getName();
        String payload   = serializeEvent(event);

        Map<String, String> fields = Map.of(
            "eventId",       event.getEventId(),
            "eventType",     event.getEventType(),
            "correlationId", event.getCorrelationId() != null ? event.getCorrelationId() : "",
            "payload",       payload
        );

        log.debug("[RedisStream] Publishing: stream={}, eventId={}", streamKey, event.getEventId());

        RecordId recordId = redisTemplate.opsForStream()
            .add(MapRecord.create(streamKey, fields));

        log.debug("[RedisStream] Published OK: stream={}, recordId={}", streamKey, recordId);
    }

    @Override
    public void publishIdempotent(DomainEvent event, String idempotencyKey) {
        // Check eventId trong Redis SET trước XADD
        String redisKey = IDEMPOTENT_PREFIX + idempotencyKey;
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(redisKey, "1", IDEMPOTENT_TTL);

        if (Boolean.FALSE.equals(isNew)) {
            log.debug("[RedisStream] Skipping duplicate: idempotencyKey={}", idempotencyKey);
            return;
        }

        publish(event);
    }

    @Override
    public EventBusCapabilities getCapabilities() {
        return EventBusCapabilities.redisStream();
    }

    // =========================================================================
    // Consumer — được gọi bởi polling loop hoặc @StreamListener
    // =========================================================================

    /**
     * Nhận message từ Redis Stream và dispatch đến handlers đã đăng ký.
     * Trong production, được gọi bởi scheduled polling thread.
     *
     * @param record     Redis Stream record chứa event data
     * @param streamKey  tên stream key (để XACK sau)
     */
    public void onStreamMessage(MapRecord<String, String, String> record, String streamKey) {
        String eventId   = record.getValue().get("eventId");
        String eventType = record.getValue().get("eventType");
        String payload   = record.getValue().get("payload");
        RecordId recordId = record.getId();

        log.debug("[RedisStream] Received: stream={}, eventType={}, recordId={}",
            streamKey, eventType, recordId);

        DomainEvent event = deserializeEvent(payload, eventType);
        if (event == null) {
            log.error("[RedisStream] Failed to deserialize: eventType={}, recordId={}", eventType, recordId);
            // XACK để không loop lại message corrupt
            xack(streamKey, recordId.getValue());
            return;
        }

        Acknowledgment ack = new RedisStreamAcknowledgment(redisTemplate, streamKey,
            recordId.getValue(), event);
        dispatch(event, ack);
    }

    private void xack(String streamKey, String messageId) {
        try {
            redisTemplate.opsForStream().acknowledge(streamKey, CONSUMER_GROUP, messageId);
        } catch (Exception e) {
            log.error("[RedisStream] XACK failed: stream={}, messageId={}, error={}",
                streamKey, messageId, e.getMessage());
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
        log.debug("[RedisStream] Deserializing: eventType={}", eventType);
        return null; // Production: dùng ObjectMapper với class registry
    }

    // =========================================================================
    // Inner class: RedisStreamAcknowledgment
    // =========================================================================

    private static class RedisStreamAcknowledgment implements Acknowledgment {

        private final StringRedisTemplate redisTemplate;
        private final String streamKey;
        private final String messageId;
        private final DomainEvent event;

        RedisStreamAcknowledgment(StringRedisTemplate redisTemplate,
                                   String streamKey, String messageId,
                                   DomainEvent event) {
            this.redisTemplate = redisTemplate;
            this.streamKey     = streamKey;
            this.messageId     = messageId;
            this.event         = event;
        }

        @Override
        public void acknowledge() {
            // XACK — message rời khỏi PEL (Pending Entry List)
            redisTemplate.opsForStream().acknowledge(streamKey, CONSUMER_GROUP, messageId);
            log.debug("[RedisStream-Ack] Acknowledged: eventId={}, messageId={}",
                event.getEventId(), messageId);
        }

        @Override
        public void reject() {
            reject(true);
        }

        @Override
        public void reject(boolean requeue) {
            if (requeue) {
                // Không XACK → message ở lại PEL → consumer group re-deliver sau timeout
                event.incrementRetryCount();
                log.warn("[RedisStream-Ack] Rejected (retry via PEL): eventId={}, retryCount={}",
                    event.getEventId(), event.getRetryCount());
            } else {
                // XACK để xóa khỏi PEL, nhưng publish vào dead letter stream
                redisTemplate.opsForStream().acknowledge(streamKey, CONSUMER_GROUP, messageId);
                String dlqKey = streamKey + ".dlq";
                redisTemplate.opsForStream().add(
                    MapRecord.create(dlqKey, Map.of(
                        "originalMessageId", messageId,
                        "eventId",           event.getEventId(),
                        "eventType",         event.getEventType()
                    ))
                );
                log.warn("[RedisStream-Ack] Rejected (dead letter): eventId={}, dlq={}",
                    event.getEventId(), dlqKey);
            }
        }
    }
}
