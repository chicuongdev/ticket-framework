# hcr-gateway — Hướng dẫn đọc code

> **Vai trò:** Tầng bảo vệ đầu tiên — mọi request đều đi qua pipeline
> Validate → Idempotency → Rate Limit → Circuit Breaker trước khi vào Saga.
>
> **Dependency:** hcr-core, hcr-saga (đã hoàn thành).

---

## Thứ tự đọc (quan trọng)

### 1. `ratelimit/RateLimitResult.java` — Kết quả rate limit check

Đọc đầu tiên vì nhiều class dùng type này.

- 4 fields: `allowed`, `remainingPermits`, `resetAfterMs`, `limitPerSecond`
- Factory: `allowed(...)` / `denied(...)`
- Dùng để set HTTP headers: `X-RateLimit-Remaining`, `X-RateLimit-Reset`

### 2. `ratelimit/RateLimiter.java` — Interface rate limiting

Contract 4 methods:

| Method | Mô tả |
|--------|-------|
| `tryAcquire(key)` | Lấy 1 permit, trả boolean |
| `tryAcquire(key, permits)` | Lấy n permits, trả boolean |
| `tryAcquireWithInfo(key)` | Lấy 1 permit + thông tin đầy đủ |
| `configure(key, rps, burst)` | Config per-key (VIP user, hot resource) |

**Key là gì?** Phân biệt "bucket" riêng biệt — thường là `requesterId` (per user),
`resourceId` (per resource), hoặc composite.

### 3. `ratelimit/RateLimitExceededException.java` — Exception khi vượt limit

- Không extend `FrameworkException` — rate limiting là behavior bình thường, không phải lỗi hệ thống
- Mang `RateLimitResult` để developer set HTTP headers trong exception handler
- Map → HTTP 429 Too Many Requests

### 4. `idempotency/IdempotencyHandler.java` — Interface chống duplicate

Contract 4 methods:

| Method | Mô tả |
|--------|-------|
| `isDuplicate(key)` | Key đã xử lý chưa? |
| `markProcessed(key, result)` | Đánh dấu đã xử lý + cache result |
| `getCachedResult(key)` | Lấy kết quả đã cache |
| `expire(key)` | Xóa cache thủ công |

**Khi nào cần idempotency?** Client retry sau timeout → framework detect duplicate
qua `idempotencyKey` → ném `IdempotencyException` → client biết request đã xử lý.

### 5. `ratelimit/redis/RedisTokenBucketRateLimiter.java` — Token Bucket trên Redis

Đọc song song với Lua script.

**Token Bucket algorithm:**
```
Bucket có capacity C và refill rate R (tokens/giây).
Mỗi request tiêu 1 token.
Nếu hết token → từ chối (429).
Token tự refill theo thời gian: elapsed_ms × R / 1000.
```

**Tại sao Lua script?** Redis đảm bảo atomicity — GET + compute + SET là 1 operation,
không có race condition giữa nhiều server instances.

**Lua script:** `src/main/resources/lua/rate_limit_token_bucket.lua`

```
KEYS[1] = "hcr:ratelimit:{key}:tokens"  — số token hiện có
KEYS[2] = "hcr:ratelimit:{key}:ts"      — timestamp lần cập nhật cuối (ms)

ARGV[1] = requested permits
ARGV[2] = permits per second
ARGV[3] = burst capacity
ARGV[4] = current time ms

Return: {allowed(0|1), remaining, resetAfterMs, limitPerSecond}
```

**Fail open:** Nếu Redis lỗi → `tryAcquireWithInfo` trả `allowed=true` (log warning).
Tránh block toàn bộ traffic chỉ vì Redis rate limiter tạm thời down.

**Per-key config:** `configure(key, rps, burst)` override default cho key cụ thể.
Lưu trong `ConcurrentHashMap` — in-memory, reset khi restart. Phù hợp cho config tĩnh
(VIP users, hot resources). Config động nên dùng Redis-backed config.

### 6. `idempotency/redis/RedisIdempotencyHandler.java` — Redis idempotency

```
SET "hcr:idempotency:{key}" {orderId} EX {ttlSeconds}
EXISTS "hcr:idempotency:{key}"
GET "hcr:idempotency:{key}"
DEL "hcr:idempotency:{key}"
```

- TTL mặc định: 86400 giây (24 giờ)
- Value lưu: `result.toString()` — trong framework là orderId (String)
- `getCachedResult(key)` trả `Optional<Object>` → caller tự cast sang String (orderId)

### 7. `AbstractRequestValidator.java` — Standalone validation helper

2-layer validation pattern:

```
validate(request):
  basic    = validateBasicFields(request)    // framework: null/blank/range check
  business = validateBusinessRules(request)  // developer: business rules
  return basic.merge(business)               // gộp tất cả lỗi
```

**Khi nào dùng?** Khi muốn tách validation ra class riêng để reuse hoặc test độc lập.
`FrameworkGateway` đã tích hợp sẵn pattern này — dùng khi không cần gateway.

### 8. `filter/CorrelationIdFilter.java` — Distributed tracing

Đọc class này để hiểu cách correlationId được propagate.

**Pipeline:**
```
Request vào:
  1. Lấy "X-Correlation-ID" header hoặc sinh UUID mới
  2. Set MDC["correlationId"] → mọi log tự động include
  3. Set response header → client có thể dùng để report lỗi

Request xong:
  4. MDC.remove("correlationId") → PHẢI làm, tránh leak sang request khác
```

**Logback config cần thêm `%X{correlationId}` vào pattern:**
```xml
<pattern>%d{HH:mm:ss} %-5level [%X{correlationId}] %logger{36} - %msg%n</pattern>
```

Đăng ký với Spring Boot với `Ordered.HIGHEST_PRECEDENCE` — phải chạy TRƯỚC mọi filter khác.

### 9. `FrameworkGateway.java` — TRUNG TÂM MODULE

**Đọc kỹ class này nhất.** Template Method pattern điều phối toàn bộ pipeline.

#### Method `submit()` — final, 6 bước:

```
1. validateBasicFields()      framework — không thể skip
   + validateBusinessRules()  developer implement
   → fail: ValidationException (→ HTTP 400)

2. isDuplicate(idempotencyKey)?
   → YES: IdempotencyException (→ HTTP 409)
   → NO: tiếp tục

3. shouldRateLimit() && rateLimiter != null?
   → tryAcquireWithInfo(getRateLimitKey())
   → denied: RateLimitExceededException (→ HTTP 429)

4. isCircuitBreakerOpen()?
   → YES: FrameworkException(SYSTEM_ERROR) (→ HTTP 503)
   → NO: tiếp tục

5. orchestrator.process(request)
   → Sync (P1/P2): order CONFIRMED (→ HTTP 201)
   → Async (P3):   order RESERVED  (→ HTTP 202)

6. markProcessed(idempotencyKey, order.getOrderId())
   → Cache orderId để detect duplicate retry
```

#### Methods developer BẮT BUỘC implement:

| Method | Mô tả |
|--------|-------|
| `validateBusinessRules(request)` | Business validation — trả ValidationResult |

#### Methods developer CÓ THỂ override:

| Method | Default | Khi nào override |
|--------|---------|-----------------|
| `shouldRateLimit(request)` | true | Disable cho admin/internal |
| `getRateLimitKey(request)` | requesterId | Dùng IP hoặc composite key |
| `getIdempotencyKey(request)` | idempotencyKey | Custom key generation |
| `isCircuitBreakerOpen()` | false | Wire với CircuitBreakerInventoryDecorator |

#### Circuit Breaker wiring:

```java
// Developer inject CB decorator rồi override method này:
@Override
protected boolean isCircuitBreakerOpen() {
    return circuitBreaker.getState() == CircuitBreakerState.OPEN;
}
```

`FrameworkGateway` không trực tiếp import `CircuitBreakerInventoryDecorator` —
developer wire theo cách này để loose coupling.

#### RateLimiter = null:

```java
// Constructor không truyền rateLimiter → tắt hoàn toàn
super(orchestrator, idempotencyHandler);
// Hoặc:
super(orchestrator, idempotencyHandler, null);
```

---

## Cấu trúc package

```
io.hrc.gateway
├── FrameworkGateway.java          ← Entry point (đọc cuối)
├── AbstractRequestValidator.java  ← Standalone validator
├── filter/
│   └── CorrelationIdFilter.java   ← MDC + tracing
├── idempotency/
│   ├── IdempotencyHandler.java    ← Interface
│   └── redis/
│       └── RedisIdempotencyHandler.java
└── ratelimit/
    ├── RateLimiter.java           ← Interface
    ├── RateLimitResult.java       ← Result object (đọc đầu)
    ├── RateLimitExceededException.java
    └── redis/
        └── RedisTokenBucketRateLimiter.java
```

---

## Exception → HTTP status mapping

Developer đăng ký exception handlers để map:

| Exception | HTTP Status | Ý nghĩa |
|-----------|-------------|---------|
| `ValidationException` | 400 Bad Request | Request không hợp lệ |
| `IdempotencyException` | 409 Conflict | Request đã được xử lý |
| `RateLimitExceededException` | 429 Too Many Requests | Vượt rate limit |
| `FrameworkException(SYSTEM_ERROR)` | 503 Service Unavailable | CB đang OPEN |
| `InsufficientInventoryException` | 409 Conflict | Hết hàng |

---

## Ví dụ developer sử dụng đầy đủ

```java
@Service
public class ConcertTicketGateway
        extends FrameworkGateway<BookTicketRequest, ConcertOrder> {

    private final ConcertRepository concertRepo;
    private final CircuitBreakerInventoryDecorator circuitBreaker;

    public ConcertTicketGateway(ConcertTicketSaga orchestrator,
                                 RedisIdempotencyHandler idempotencyHandler,
                                 RedisTokenBucketRateLimiter rateLimiter,
                                 ConcertRepository concertRepo,
                                 CircuitBreakerInventoryDecorator circuitBreaker) {
        super(orchestrator, idempotencyHandler, rateLimiter);
        this.concertRepo   = concertRepo;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    protected ValidationResult validateBusinessRules(BookTicketRequest request) {
        ValidationResult result = ValidationResult.ok();

        Concert concert = concertRepo.findById(request.getResourceId()).orElse(null);
        if (concert == null) {
            return ValidationResult.fail("resourceId", "Concert không tồn tại");
        }
        if (concert.hasEnded()) {
            result = result.merge(ValidationResult.fail("resourceId", "Concert đã kết thúc"));
        }
        if (request.getQuantity() > 4) {
            result = result.merge(ValidationResult.fail("quantity",
                "Tối đa 4 vé mỗi lần đặt", request.getQuantity()));
        }

        return result;
    }

    @Override
    protected boolean isCircuitBreakerOpen() {
        return circuitBreaker.getState() == CircuitBreakerState.OPEN;
    }

    @Override
    protected String getRateLimitKey(BookTicketRequest request) {
        // Per user per concert — tránh 1 user spam 1 concert cụ thể
        return request.getRequesterId() + ":" + request.getResourceId();
    }
}
```

```java
// REST Controller gọi gateway:
@RestController
@RequestMapping("/api/concerts")
public class ConcertController {

    private final ConcertTicketGateway gateway;

    @PostMapping("/{concertId}/book")
    public ResponseEntity<OrderResponse> bookTicket(@PathVariable String concertId,
                                                     @RequestBody BookTicketRequest request) {
        ConcertOrder order = gateway.submit(request);

        HttpStatus status = order.getStatus() == OrderStatus.CONFIRMED
            ? HttpStatus.CREATED       // 201 — Sync/P1/P2
            : HttpStatus.ACCEPTED;     // 202 — Async/P3

        return ResponseEntity.status(status).body(OrderResponse.from(order));
    }
}
```
