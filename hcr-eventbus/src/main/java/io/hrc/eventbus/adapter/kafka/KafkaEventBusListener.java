package io.hrc.eventbus.adapter.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;

/**
 * Spring bean nhận message từ tất cả topic match pattern {@code hcr\..*}
 * và đẩy sang {@link KafkaEventBusAdapter#onKafkaMessage}.
 *
 * <p><b>Điều kiện:</b> {@code @KafkaListener} cần container factory đã cấu hình
 * {@code ackMode=MANUAL_IMMEDIATE}. {@link io.hrc.autoconfigure} sẽ wire sẵn.
 *
 * <p>Consumer group mặc định: {@code hcr-consumer-group} — tất cả instance cùng
 * application chia partition; muốn broadcast thì override qua property.
 */
@Slf4j
public class KafkaEventBusListener {

    private final KafkaEventBusAdapter adapter;

    public KafkaEventBusListener(KafkaEventBusAdapter adapter) {
        this.adapter = adapter;
    }

    @KafkaListener(
            topicPattern = "${hcr.event-bus.kafka.topic-pattern:hcr\\..*}",
            groupId = "${hcr.event-bus.kafka.consumer-group:hcr-consumer-group}",
            containerFactory = "hcrKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String eventType = header(record, KafkaEventBusAdapter.HEADER_EVENT_TYPE);
        if (eventType == null) {
            log.warn("[Kafka-Listener] Missing {} header — skipping: topic={}, offset={}",
                    KafkaEventBusAdapter.HEADER_EVENT_TYPE,
                    record.topic(), record.offset());
            ack.acknowledge();
            return;
        }
        adapter.onKafkaMessage(record.value(), eventType, ack);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var h = record.headers().lastHeader(name);
        return h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
    }
}
