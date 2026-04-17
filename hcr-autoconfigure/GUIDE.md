# hcr-autoconfigure — Hướng dẫn đọc code

## Mục đích
Zero boilerplate wiring: developer thêm `@EnableHighConcurrencyResource` lên main class
và viết `application.yml` là framework tự tạo tất cả beans cần thiết.

## Thứ tự đọc code

1. **`HcrProperties.java`** — Toàn bộ config `hcr.*` được map thành Java object.
   Các nested class: `InventoryProperties`, `SagaProperties`, `PaymentProperties`,
   `EventBusProperties`, `GatewayProperties`, `ReconciliationProperties`.

2. **`annotation/EnableHighConcurrencyResource.java`** — Annotation đặt trên `@SpringBootApplication`.
   Import `HcrAutoConfiguration.class` vào context.

3. **`condition/ConditionalOnInventoryStrategy.java`** — Custom condition annotation.
   Backing class: `OnInventoryStrategyCondition` đọc `hcr.inventory.strategy` từ environment.

4. **`HcrAutoConfiguration.java`** — Trung tâm module. `@AutoConfiguration` + `@EnableConfigurationProperties`.
   Tạo beans theo thứ tự:
   - EventBus: `InMemoryEventBusAdapter` nếu `hcr.event-bus.type=in-memory` (hoặc default)
   - PaymentGateway: `MockPaymentGateway` nếu không có `@Bean PaymentGateway`
   - FrameworkMetrics: `MicrometerFrameworkMetrics` nếu có Micrometer, else `NO_OP`
   - IdempotencyHandler: `RedisIdempotencyHandler` nếu có Redis
   - RateLimiter: `RedisTokenBucketRateLimiter` nếu `hcr.gateway.rate-limiter.enabled=true`
   - CorrelationIdFilter: luôn đăng ký ở `HIGHEST_PRECEDENCE`
   - HcrActuatorEndpoint: nếu spring-boot-actuator trên classpath

5. **`actuator/HcrActuatorEndpoint.java`** — `GET /actuator/hcr` trả về config đang active.

6. **`filter/CorrelationIdFilter.java`** — Marker class. Implementation thực tế ở `hcr-gateway`.

## META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```
io.hrc.autoconfigure.HcrAutoConfiguration
```
File này cho phép Spring Boot tự động discover `HcrAutoConfiguration` khi module nằm trên classpath.

## Cách dùng cơ bản

```yaml
# application.yml
hcr:
  inventory:
    strategy: pessimistic-lock   # pessimistic-lock | optimistic-lock | redis-atomic
  saga:
    mode: sync
  event-bus:
    type: in-memory
```

```java
@SpringBootApplication
@EnableHighConcurrencyResource
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

## InventoryStrategy — developer PHẢI tự wire

`HcrAutoConfiguration` **KHÔNG** tự tạo `InventoryStrategy` bean vì nó cần:
- `EntityManager` (Spring JPA cung cấp)
- `entityClass` (developer-specific, không thể đoán được)

Developer phải khai báo bean này thủ công:
```java
@Bean
public InventoryStrategy inventoryStrategy(EntityManager em,
                                            TransactionTemplate tx,
                                            ApplicationEventPublisher pub,
                                            InventoryMetrics metrics) {
    InventoryStrategyFactory factory = new InventoryStrategyFactory(
        em, ConcertTicket.class, tx, pub, metrics, null, null);
    return factory.create("pessimistic-lock");
}
```

## Override bất kỳ bean nào

Tất cả beans đều có `@ConditionalOnMissingBean` → developer khai báo `@Bean` cùng type
là tự động override:

```java
@Bean
public PaymentGateway stripeGateway() {
    return new StripePaymentGateway(stripeApiKey);
}
```
