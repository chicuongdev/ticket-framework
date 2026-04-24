package io.hrc.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.EventTypeRegistry;
import io.hrc.eventbus.adapter.kafka.KafkaEventBusAdapter;
import io.hrc.eventbus.adapter.kafka.KafkaEventBusListener;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Auto-config Kafka EventBus — kích hoạt khi:
 * <ul>
 *   <li>{@code spring-kafka} có trên classpath (class {@code KafkaTemplate} tồn tại)</li>
 *   <li>{@code hcr.event-bus.type=kafka}</li>
 * </ul>
 *
 * <p>Wire đầy đủ producer (acks=all, idempotence=true) + consumer (manual ack) +
 * {@link KafkaEventBusAdapter} + {@link KafkaEventBusListener} + {@link EventTypeRegistry}.
 *
 * <p>Developer có thể override từng bean bằng cách tự khai báo {@code @Bean}
 * cùng tên hoặc cùng type — tất cả đều có {@code @ConditionalOnMissingBean}.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "hcr.event-bus", name = "type", havingValue = "kafka")
@EnableKafka
@Slf4j
public class KafkaEventBusAutoConfiguration {

    // =========================================================================
    // Jackson ObjectMapper (dùng chung cho serialize/deserialize event)
    // =========================================================================

    @Bean("hcrEventObjectMapper")
    @ConditionalOnMissingBean(name = "hcrEventObjectMapper")
    public ObjectMapper hcrEventObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());   // Instant
        mapper.registerModule(new ParameterNamesModule()); // constructor param names
        log.info("[HCR-Kafka] ObjectMapper registered (JavaTime + ParameterNames)");
        return mapper;
    }

    // =========================================================================
    // EventTypeRegistry — scan io.hrc.* để map simpleName → Class
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(EventTypeRegistry.class)
    public EventTypeRegistry hcrEventTypeRegistry() {
        return new EventTypeRegistry("io.hrc");
    }

    // =========================================================================
    // Producer
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(ProducerFactory.class)
    public ProducerFactory<String, String> hcrKafkaProducerFactory(HcrProperties properties) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getEventBus().getKafka().getBootstrapServers());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(ProducerConfig.RETRIES_CONFIG, 3);
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaTemplate.class)
    public KafkaTemplate<String, String> hcrKafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    // =========================================================================
    // Consumer + listener container factory
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(ConsumerFactory.class)
    public ConsumerFactory<String, String> hcrKafkaConsumerFactory(HcrProperties properties) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getEventBus().getKafka().getBootstrapServers());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean("hcrKafkaListenerContainerFactory")
    @ConditionalOnMissingBean(name = "hcrKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> hcrKafkaListenerContainerFactory(
            ConsumerFactory<String, String> cf) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }

    // =========================================================================
    // KafkaAdmin — cho phép framework/dev tạo topic runtime nếu cần
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(KafkaAdmin.class)
    public KafkaAdmin hcrKafkaAdmin(HcrProperties properties) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                properties.getEventBus().getKafka().getBootstrapServers());
        return new KafkaAdmin(cfg);
    }

    // =========================================================================
    // Adapter + Listener
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    public EventBus kafkaEventBus(KafkaTemplate<String, String> kafkaTemplate,
                                    @org.springframework.beans.factory.annotation.Qualifier("hcrEventObjectMapper")
                                    ObjectMapper objectMapper,
                                    EventTypeRegistry registry,
                                    HcrProperties properties) {
        String prefix = properties.getEventBus().getKafka().getTopicPrefix();
        log.info("[HCR] EventBus: Kafka (bootstrap={}, topic-prefix={})",
                properties.getEventBus().getKafka().getBootstrapServers(), prefix);
        return new KafkaEventBusAdapter(kafkaTemplate, objectMapper, registry, prefix);
    }

    @Bean
    @ConditionalOnMissingBean(KafkaEventBusListener.class)
    public KafkaEventBusListener kafkaEventBusListener(EventBus eventBus) {
        if (!(eventBus instanceof KafkaEventBusAdapter kafkaAdapter)) {
            throw new IllegalStateException(
                    "hcr.event-bus.type=kafka nhưng EventBus bean không phải KafkaEventBusAdapter: "
                            + eventBus.getClass().getName());
        }
        return new KafkaEventBusListener(kafkaAdapter);
    }
}
