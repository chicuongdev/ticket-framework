package io.hrc.eventbus.adapter.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hrc.core.domain.DomainEvent;
import io.hrc.eventbus.Acknowledgment;
import io.hrc.eventbus.EventBusCapabilities;
import io.hrc.eventbus.EventDestination;
import io.hrc.eventbus.EventTypeRegistry;
import io.hrc.eventbus.adapter.AbstractEventBusAdapter;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Adapter cho Apache Kafka — default implementation cho production high-load.
 *
 * <p><b>Config được framework set sẵn (at-least-once guarantee):</b>
 * <ul>
 *   <li>{@code acks=all} — đảm bảo không mất message khi broker fail.</li>
 *   <li>{@code retries=3} — retry khi network error.</li>
 *   <li>{@code enable.idempotence=true} — không duplicate khi producer retry.</li>
 *   <li>{@code ack-mode=manual_immediate} — consumer chỉ ack sau khi xử lý xong.</li>
 * </ul>
 *
 * <p><b>Routing:</b> {@code EventDestination.name} → topic {@code "hcr.{name}"}.
 * Prefix {@code "hcr."} configurable qua {@code hcr.event-bus.kafka.topic-prefix}.
 *
 * <p><b>Serialization:</b> Jackson JSON. Header {@code X-Event-Type} giữ
 * {@code event.getClass().getSimpleName()} để consumer biết deserialize ra class nào.
 *
 * <p><b>Partitioning:</b> Dùng {@code resourceId} làm partition key — đảm bảo
 * tất cả event của cùng 1 resource đi vào cùng 1 partition (ordering guaranteed).
 *
 * <p><b>Dependency:</b> Cần {@code spring-kafka} + {@code jackson-databind} trong classpath.
 */
@Slf4j
public class KafkaEventBusAdapter extends AbstractEventBusAdapter {

    public static final String HEADER_EVENT_TYPE = "X-Event-Type";
    private static final String DEFAULT_TOPIC_PREFIX = "hcr.";

    private final KafkaTemplate<String, String> kafkaTemplate;
    @Getter
    private final String topicPrefix;
    private final ObjectMapper objectMapper;
    private final EventTypeRegistry eventTypeRegistry;

    public KafkaEventBusAdapter(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 EventTypeRegistry eventTypeRegistry) {
        this(kafkaTemplate, objectMapper, eventTypeRegistry, DEFAULT_TOPIC_PREFIX);
    }

    public KafkaEventBusAdapter(KafkaTemplate<String, String> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 EventTypeRegistry eventTypeRegistry,
                                 String topicPrefix) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.eventTypeRegistry = eventTypeRegistry;
        this.topicPrefix = topicPrefix;
    }

    @Override
    public void publish(DomainEvent event) {
        EventDestination destination = EventDestination.forEventType(event.getClass());
        publish(event, destination);
    }

    @Override
    public void publish(DomainEvent event, EventDestination destination) {
        String topic = topicPrefix + destination.getName();
        String partitionKey = event.getResourceId() != null ? event.getResourceId() : event.getEventId();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[Kafka] Serialization failed: eventId={}, type={}",
                    event.getEventId(), event.getEventType(), e);
            return;
        }

        ProducerRecord<String, String> record = new ProducerRecord<>(topic, null, partitionKey, payload);
        record.headers().add(new RecordHeader(HEADER_EVENT_TYPE,
                event.getEventType().getBytes(StandardCharsets.UTF_8)));

        log.debug("[Kafka] Publishing: topic={}, key={}, eventId={}, type={}",
                topic, partitionKey, event.getEventId(), event.getEventType());

        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[Kafka] Publish failed: topic={}, eventId={}, error={}",
                        topic, event.getEventId(), ex.getMessage(), ex);
                eventBusMetrics.recordEventFailed(event.getEventType(), "PUBLISH_FAILED");
            } else {
                log.debug("[Kafka] Publish success: topic={}, partition={}, offset={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                eventBusMetrics.recordEventPublished(event.getEventType(), "kafka");
            }
        });
    }

    @Override
    public void publishIdempotent(DomainEvent event, String idempotencyKey) {
        // Kafka idempotent producer (enable.idempotence=true) đảm bảo không duplicate
        // khi producer retry → không cần Redis check thêm
        log.debug("[Kafka] Publishing idempotent: eventId={}, idempotencyKey={}",
                event.getEventId(), idempotencyKey);
        publish(event);
    }

    @Override
    public EventBusCapabilities getCapabilities() {
        return EventBusCapabilities.kafka();
    }

    // =========================================================================
    // Dispatch path — gọi bởi KafkaEventBusListener khi nhận message
    // =========================================================================

    /**
     * Deserialize payload từ Kafka rồi gọi {@link #dispatch(DomainEvent, Acknowledgment)}.
     *
     * @param payload       JSON payload
     * @param eventTypeName simple class name từ header {@code X-Event-Type}
     * @param kafkaAck      Acknowledgment từ Spring Kafka (sẽ được wrap)
     */
    public void onKafkaMessage(String payload, String eventTypeName,
                                org.springframework.kafka.support.Acknowledgment kafkaAck) {
        Class<? extends DomainEvent> eventClass = eventTypeRegistry.lookup(eventTypeName);
        if (eventClass == null) {
            log.warn("[Kafka] Unknown event type: {} — skipping (will ack). Payload: {}",
                    eventTypeName, payload);
            kafkaAck.acknowledge();
            return;
        }

        DomainEvent event;
        try {
            event = objectMapper.readValue(payload, eventClass);
        } catch (JsonProcessingException e) {
            log.error("[Kafka] Deserialization failed: type={}, payload={}",
                    eventTypeName, payload, e);
            kafkaAck.acknowledge(); // skip bad message — tránh infinite retry
            return;
        }

        log.debug("[Kafka] Dispatching: eventId={}, type={}", event.getEventId(), eventTypeName);
        Acknowledgment ack = new KafkaAcknowledgment(kafkaAck, event);
        dispatch(event, ack);
    }

    // =========================================================================
    // Inner class: KafkaAcknowledgment
    // =========================================================================

    private static class KafkaAcknowledgment implements Acknowledgment {

        private final org.springframework.kafka.support.Acknowledgment kafkaAck;
        private final DomainEvent event;

        KafkaAcknowledgment(org.springframework.kafka.support.Acknowledgment kafkaAck,
                             DomainEvent event) {
            this.kafkaAck = kafkaAck;
            this.event = event;
        }

        @Override
        public void acknowledge() {
            kafkaAck.acknowledge();
            log.debug("[Kafka-Ack] Acknowledged: eventId={}", event.getEventId());
        }

        @Override
        public void reject() {
            reject(true);
        }

        @Override
        public void reject(boolean requeue) {
            if (requeue) {
                log.warn("[Kafka-Ack] Rejected (retry): eventId={}", event.getEventId());
                event.incrementRetryCount();
                // Không ack → Kafka sẽ re-deliver (consumer seek behavior của container)
            } else {
                kafkaAck.acknowledge();
                log.warn("[Kafka-Ack] Rejected (dead letter): eventId={}", event.getEventId());
            }
        }
    }
}
