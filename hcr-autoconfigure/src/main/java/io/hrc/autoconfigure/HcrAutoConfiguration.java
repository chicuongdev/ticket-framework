package io.hrc.autoconfigure;

import io.hrc.autoconfigure.actuator.HcrActuatorEndpoint;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.adapter.inmemory.InMemoryEventBusAdapter;
import io.hrc.gateway.filter.CorrelationIdFilter;
import io.hrc.gateway.idempotency.IdempotencyHandler;
import io.hrc.gateway.idempotency.redis.RedisIdempotencyHandler;
import io.hrc.gateway.ratelimit.RateLimiter;
import io.hrc.gateway.ratelimit.redis.RedisTokenBucketRateLimiter;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.observability.micrometer.MicrometerFrameworkMetrics;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.gateway.mock.MockPaymentGateway;
import io.hrc.payment.handler.TimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Tự động wire tất cả beans của HCR framework dựa trên YAML config.
 *
 * <p>Kích hoạt qua {@code @EnableHighConcurrencyResource} hoặc auto-configure classpath.
 *
 * <p><b>Thứ tự ưu tiên bean:</b>
 * <ol>
 *   <li>Developer tự khai báo {@code @Bean} → override hoàn toàn</li>
 *   <li>Auto-configuration này → fallback an toàn</li>
 * </ol>
 *
 * <p><b>Fail-fast khi startup:</b>
 * <ul>
 *   <li>{@code hcr.event-bus.type=kafka} nhưng thiếu Kafka dependency → exception</li>
 *   <li>{@code hcr.inventory.strategy=redis-atomic} nhưng thiếu Redis bean → exception</li>
 *   <li>{@code hcr.saga.mode=async} nhưng thiếu SagaStateRepository → exception</li>
 * </ul>
 */
@AutoConfiguration
@EnableConfigurationProperties(HcrProperties.class)
@Slf4j
public class HcrAutoConfiguration {

    // =========================================================================
    // EventBus
    // =========================================================================

    /**
     * Tạo InMemoryEventBusAdapter khi type=in-memory (mặc định hoặc test).
     * Luôn là fallback — không cần infrastructure.
     */
    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    @ConditionalOnProperty(prefix = "hcr.event-bus", name = "type",
            havingValue = "in-memory", matchIfMissing = true)
    public EventBus inMemoryEventBus() {
        log.info("[HCR] EventBus: InMemory (đồng bộ, không có persistence — KHÔNG dùng cho production)");
        return new InMemoryEventBusAdapter();
    }

    // Adapter khác (Kafka, RabbitMQ, Redis Streams): developer khai báo @Bean EventBus tự wire,
    // auto-config InMemory sẽ bị override bởi @ConditionalOnMissingBean.

    // =========================================================================
    // PaymentGateway — default là MockPaymentGateway (developer PHẢI override)
    // =========================================================================

    /**
     * MockPaymentGateway — CHỈ kích hoạt khi developer set
     * {@code hcr.payment.mock-enabled=true} (test/dev only).
     *
     * <p>Production: KHÔNG bật flag này — developer PHẢI khai báo {@code @Bean PaymentGateway}
     * trỏ tới gateway thật. Nếu thiếu, app fail-fast khi startup (tốt hơn là thanh toán giả).
     */
    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    @ConditionalOnProperty(prefix = "hcr.payment", name = "mock-enabled", havingValue = "true")
    public PaymentGateway mockPaymentGateway() {
        log.warn("[HCR] PaymentGateway: MockPaymentGateway (tỉ lệ thành công 80%) — " +
                 "CHỈ DÙNG CHO DEV/TEST. Production phải khai báo @Bean PaymentGateway thật.");
        TimeoutHandler timeoutHandler = new TimeoutHandler(null, 100, 1);
        return MockPaymentGateway.builder()
                .successRate(0.80)
                .simulatedDelayMs(0)
                .timeoutRate(0.0)
                .noResponseRate(0.0)
                .lateSuccessRate(0.0)
                .timeoutHandler(timeoutHandler)
                .maxRetries(1)
                .timeoutMs(5000)
                .build();
    }

    // =========================================================================
    // FrameworkMetrics
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(FrameworkMetrics.class)
    @ConditionalOnClass(MeterRegistry.class)
    public FrameworkMetrics frameworkMetrics(MeterRegistry registry) {
        log.info("[HCR] FrameworkMetrics: MicrometerFrameworkMetrics");
        return new MicrometerFrameworkMetrics(registry);
    }

    @Bean
    @ConditionalOnMissingBean(FrameworkMetrics.class)
    public FrameworkMetrics noOpFrameworkMetrics() {
        log.warn("[HCR] FrameworkMetrics: NoOp (Micrometer không có trên classpath)");
        return FrameworkMetrics.NO_OP;
    }

    // =========================================================================
    // Gateway — IdempotencyHandler và RateLimiter
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(IdempotencyHandler.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    public IdempotencyHandler redisIdempotencyHandler(HcrProperties properties,
                                                        StringRedisTemplate redisTemplate) {
        long ttl = properties.getGateway().getIdempotencyTtlSeconds();
        log.info("[HCR] IdempotencyHandler: Redis (ttl={}s)", ttl);
        return new RedisIdempotencyHandler(redisTemplate, ttl);
    }

    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    @ConditionalOnProperty(prefix = "hcr.gateway.rate-limiter", name = "enabled", havingValue = "true")
    @ConditionalOnBean(StringRedisTemplate.class)
    public RateLimiter redisTokenBucketRateLimiter(HcrProperties properties,
                                                     StringRedisTemplate redisTemplate) {
        HcrProperties.RateLimiterProperties rl = properties.getGateway().getRateLimiter();
        log.info("[HCR] RateLimiter: RedisTokenBucket (permits/s={}, burst={})",
                rl.getPermitsPerSecond(), rl.getBurstCapacity());
        return new RedisTokenBucketRateLimiter(
                redisTemplate,
                rl.getPermitsPerSecond(),
                rl.getBurstCapacity());
    }

    // =========================================================================
    // CorrelationIdFilter
    // =========================================================================

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        registration.setName("hcrCorrelationIdFilter");
        log.info("[HCR] CorrelationIdFilter đã đăng ký tại HIGHEST_PRECEDENCE");
        return registration;
    }

    // =========================================================================
    // Actuator endpoint
    // =========================================================================

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean(HcrActuatorEndpoint.class)
    public HcrActuatorEndpoint hcrActuatorEndpoint(HcrProperties properties,
                                                    @Autowired(required = false) InventoryStrategy inventoryStrategy,
                                                    @Autowired(required = false) EventBus eventBus) {
        return new HcrActuatorEndpoint(properties, inventoryStrategy, eventBus);
    }
}
