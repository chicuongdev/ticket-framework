package io.hrc.autoconfigure;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * YAML-to-Java mapping cho toàn bộ config {@code hcr.*}.
 *
 * <pre>{@code
 * hcr:
 *   inventory:
 *     strategy: redis-atomic        # pessimistic-lock | optimistic-lock | redis-atomic
 *     circuit-breaker:
 *       enabled: true
 *       failure-rate-threshold: 50
 *       wait-duration-seconds: 60
 *   saga:
 *     mode: async                   # sync | async
 *   event-bus:
 *     type: kafka                   # kafka | rabbitmq | redis-stream | in-memory
 *   gateway:
 *     rate-limiter:
 *       enabled: true
 *       permits-per-second: 100
 *       burst-capacity: 200
 *     idempotency-ttl-seconds: 86400
 *   reconciliation:
 *     timeout-minutes: 5
 *     schedule-delay-ms: 300000
 *   payment:
 *     timeout-ms: 30000
 *     max-retries: 3
 * }</pre>
 */
@ConfigurationProperties(prefix = "hcr")
@Validated
@Getter
@Setter
public class HcrProperties {

    private InventoryProperties inventory = new InventoryProperties();
    private SagaProperties saga = new SagaProperties();
    private PaymentProperties payment = new PaymentProperties();
    private EventBusProperties eventBus = new EventBusProperties();
    private GatewayProperties gateway = new GatewayProperties();
    private ReconciliationProperties reconciliation = new ReconciliationProperties();

    // =========================================================================
    // Nested config classes
    // =========================================================================

    @Getter @Setter
    public static class InventoryProperties {
        /** pessimistic-lock | optimistic-lock | redis-atomic. Default: pessimistic-lock. */
        private String strategy = "pessimistic-lock";
        private CircuitBreakerProperties circuitBreaker = new CircuitBreakerProperties();
        private PersistenceProperties persistence = new PersistenceProperties();
        private String keyPrefix = "hcr:inventory:";
    }

    @Getter @Setter
    public static class CircuitBreakerProperties {
        private boolean enabled = false;
        private int slidingWindowSize = 10;
        /** Tỉ lệ lỗi để mở circuit (%). Default: 50. */
        private double failureRateThreshold = 50.0;
        /** Thời gian chờ trước khi thử lại (s). Default: 60. */
        private long waitDurationSeconds = 60;
    }

    @Getter @Setter
    public static class PersistenceProperties {
        /** single | batch. Default: single. */
        private String mode = "single";
        private int batchSize = 500;
        private long flushIntervalMs = 1000;
    }

    @Getter @Setter
    public static class SagaProperties {
        /** sync | async. Default: sync. */
        private String mode = "sync";
        private int reservationTimeoutMinutes = 5;
        private boolean allowPartialFulfillment = false;
    }

    @Getter @Setter
    public static class PaymentProperties {
        private long timeoutMs = 30_000L;
        private int maxRetries = 3;
        private long pollingIntervalMs = 5_000L;
        private int maxPollingAttempts = 6;
    }

    @Getter @Setter
    public static class EventBusProperties {
        /** kafka | rabbitmq | redis-stream | in-memory. Default: in-memory. */
        private String type = "in-memory";
        private KafkaProperties kafka = new KafkaProperties();
        private RabbitMQProperties rabbitmq = new RabbitMQProperties();
        private RedisStreamProperties redisStream = new RedisStreamProperties();
    }

    @Getter @Setter
    public static class KafkaProperties {
        private String bootstrapServers = "localhost:9092";
        private String topicPrefix = "hcr.";
    }

    @Getter @Setter
    public static class RabbitMQProperties {
        private String host = "localhost";
        private int port = 5672;
        private String exchange = "hcr.events";
    }

    @Getter @Setter
    public static class RedisStreamProperties {
        private String keyPrefix = "hcr:stream:";
        private String consumerGroup = "hcr-consumers";
    }

    @Getter @Setter
    public static class GatewayProperties {
        private RateLimiterProperties rateLimiter = new RateLimiterProperties();
        private long idempotencyTtlSeconds = 86_400L;
        private String idempotencyKeyPrefix = "hcr:idempotency:";
    }

    @Getter @Setter
    public static class RateLimiterProperties {
        private boolean enabled = false;
        private long permitsPerSecond = 100;
        private long burstCapacity = 200;
        private String keyPrefix = "hcr:ratelimit:";
    }

    @Getter @Setter
    public static class ReconciliationProperties {
        private int timeoutMinutes = 5;
        /** Interval giữa các lần chạy (ms). Default: 5 phút. */
        private long scheduleDelayMs = 300_000L;
        /** Delta threshold để auto-fix inventory mismatch. 0 = alert only. */
        private long inventoryMismatchThreshold = 0L;
    }
}
