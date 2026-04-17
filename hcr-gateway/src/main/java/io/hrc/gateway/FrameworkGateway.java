package io.hrc.gateway;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.domain.OrderRequest;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.exception.FrameworkException;
import io.hrc.core.exception.IdempotencyException;
import io.hrc.core.result.ValidationResult;
import io.hrc.gateway.idempotency.IdempotencyHandler;
import io.hrc.gateway.ratelimit.RateLimitExceededException;
import io.hrc.gateway.ratelimit.RateLimitResult;
import io.hrc.gateway.ratelimit.RateLimiter;
import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;
import lombok.extern.slf4j.Slf4j;

/**
 * Entry point duy nhất vào hệ thống. Mọi request đều phải đi qua pipeline
 * đầy đủ trước khi được chuyển xuống {@link AbstractSagaOrchestrator}.
 *
 * <p><b>Pipeline (theo thứ tự):</b>
 * <pre>
 * 1. Validate
 *    → Basic fields (framework): resourceId, requesterId, quantity, idempotencyKey
 *    → Business rules (developer): override validateBusinessRules()
 *    → Fail: throw ValidationException → HTTP 400
 *
 * 2. Idempotency check
 *    → isDuplicate(idempotencyKey)?
 *    → YES: throw IdempotencyException → HTTP 409 (request đã được xử lý)
 *    → NO: tiếp tục
 *
 * 3. Rate Limit (nếu shouldRateLimit = true và rateLimiter != null)
 *    → tryAcquireWithInfo(getRateLimitKey(request))
 *    → DENIED: throw RateLimitExceededException → HTTP 429
 *    → ALLOWED: tiếp tục
 *
 * 4. Circuit Breaker (nếu isCircuitBreakerOpen() = true)
 *    → throw FrameworkException(SYSTEM_ERROR) → HTTP 503
 *    → CLOSED/HALF_OPEN: tiếp tục
 *
 * 5. Submit to SagaOrchestrator
 *    → orchestrator.process(request)
 *    → Sync (P1/P2): trả về order CONFIRMED → HTTP 201
 *    → Async (P3): trả về order RESERVED → HTTP 202
 *
 * 6. Cache idempotency result
 *    → markProcessed(idempotencyKey, order.getOrderId())
 * </pre>
 *
 * <p><b>Developer PHẢI implement:</b>
 * <ul>
 *   <li>{@link #validateBusinessRules(OrderRequest)} — business rule validation</li>
 * </ul>
 *
 * <p><b>Developer CÓ THỂ override:</b>
 * <ul>
 *   <li>{@link #shouldRateLimit(OrderRequest)} — disable rate limit cho admin/internal</li>
 *   <li>{@link #getRateLimitKey(OrderRequest)} — custom key (default: requesterId)</li>
 *   <li>{@link #getIdempotencyKey(OrderRequest)} — custom key (default: idempotencyKey)</li>
 *   <li>{@link #isCircuitBreakerOpen()} — wire CB decorator vào gateway</li>
 * </ul>
 *
 * <p><b>Ví dụ developer sử dụng:</b>
 * <pre>{@code
 * @Service
 * public class ConcertTicketGateway
 *         extends FrameworkGateway<BookTicketRequest, ConcertOrder> {
 *
 *     private final ConcertRepository concertRepo;
 *     private final CircuitBreakerInventoryDecorator circuitBreaker;
 *
 *     public ConcertTicketGateway(ConcertTicketSaga orchestrator,
 *                                  RedisIdempotencyHandler idempotencyHandler,
 *                                  RedisTokenBucketRateLimiter rateLimiter,
 *                                  ConcertRepository concertRepo,
 *                                  CircuitBreakerInventoryDecorator circuitBreaker) {
 *         super(orchestrator, idempotencyHandler, rateLimiter);
 *         this.concertRepo = concertRepo;
 *         this.circuitBreaker = circuitBreaker;
 *     }
 *
 *     @Override
 *     protected ValidationResult validateBusinessRules(BookTicketRequest request) {
 *         ValidationResult result = ValidationResult.ok();
 *         Concert concert = concertRepo.findById(request.getResourceId()).orElse(null);
 *         if (concert == null) {
 *             result = result.merge(ValidationResult.fail("resourceId", "Concert không tồn tại"));
 *         } else if (concert.hasEnded()) {
 *             result = result.merge(ValidationResult.fail("resourceId", "Concert đã kết thúc"));
 *         }
 *         if (request.getQuantity() > 4) {
 *             result = result.merge(ValidationResult.fail("quantity",
 *                 "Tối đa 4 vé mỗi lần đặt", request.getQuantity()));
 *         }
 *         return result;
 *     }
 *
 *     @Override
 *     protected boolean isCircuitBreakerOpen() {
 *         return circuitBreaker.getState() == CircuitBreakerState.OPEN;
 *     }
 *
 *     @Override
 *     protected String getRateLimitKey(BookTicketRequest request) {
 *         // Rate limit per user per concert, không chỉ per user
 *         return request.getRequesterId() + ":" + request.getResourceId();
 *     }
 * }
 * }</pre>
 *
 * @param <REQ> kiểu request cụ thể (extends OrderRequest)
 * @param <O>   kiểu order cụ thể (extends AbstractOrder)
 */
@Slf4j
public abstract class FrameworkGateway<
        REQ extends OrderRequest,
        O extends AbstractOrder> {

    private final AbstractSagaOrchestrator<REQ, O> orchestrator;
    private final IdempotencyHandler idempotencyHandler;
    private final RateLimiter rateLimiter; // nullable — không bắt buộc

    /**
     * Constructor đầy đủ với rate limiter.
     *
     * @param orchestrator       Saga orchestrator để xử lý request
     * @param idempotencyHandler Handler chống duplicate request
     * @param rateLimiter        Rate limiter (có thể null — tắt rate limiting)
     */
    protected FrameworkGateway(AbstractSagaOrchestrator<REQ, O> orchestrator,
                                IdempotencyHandler idempotencyHandler,
                                RateLimiter rateLimiter) {
        this.orchestrator = orchestrator;
        this.idempotencyHandler = idempotencyHandler;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Constructor không có rate limiter (tắt rate limiting).
     *
     * @param orchestrator       Saga orchestrator
     * @param idempotencyHandler Handler chống duplicate request
     */
    protected FrameworkGateway(AbstractSagaOrchestrator<REQ, O> orchestrator,
                                IdempotencyHandler idempotencyHandler) {
        this(orchestrator, idempotencyHandler, null);
    }

    // =========================================================================
    // MAIN ENTRY POINT — final, developer KHÔNG override
    // =========================================================================

    /**
     * Xử lý 1 order request qua toàn bộ pipeline bảo vệ trước khi vào Saga.
     *
     * <p>Method này là {@code final} — framework đảm bảo KHÔNG có request nào
     * bypass validation, idempotency, rate limit, hoặc circuit breaker.
     *
     * @param request request từ client
     * @return order sau khi xử lý (CONFIRMED cho sync/P1/P2, RESERVED cho async/P3)
     * @throws io.hrc.core.exception.ValidationException   nếu request không hợp lệ
     * @throws IdempotencyException                        nếu idempotencyKey đã được xử lý
     * @throws RateLimitExceededException                  nếu vượt rate limit
     * @throws FrameworkException                          nếu circuit breaker OPEN hoặc lỗi hệ thống
     */
    public final O submit(REQ request) {
        String resourceId = request != null ? request.getResourceId() : null;
        log.debug("[Gateway] Received request: resourceId={}", resourceId);

        // === Bước 1: Validate ===
        // Basic fields (framework) + business rules (developer)
        ValidationResult basic    = validateBasicFields(request);
        ValidationResult business = validateBusinessRules(request);
        basic.merge(business).throwIfInvalid();

        String idempotencyKey = getIdempotencyKey(request);

        // === Bước 2: Idempotency check ===
        if (idempotencyHandler.isDuplicate(idempotencyKey)) {
            log.info("[Gateway] Duplicate request detected: idempotencyKey={}", idempotencyKey);
            throw new IdempotencyException(idempotencyKey);
        }

        // === Bước 3: Rate Limit ===
        if (rateLimiter != null && shouldRateLimit(request)) {
            String rateLimitKey = getRateLimitKey(request);
            RateLimitResult rl = rateLimiter.tryAcquireWithInfo(rateLimitKey);
            if (!rl.isAllowed()) {
                log.warn("[Gateway] Rate limit exceeded: key={}, remaining={}, resetAfterMs={}",
                        rateLimitKey, rl.getRemainingPermits(), rl.getResetAfterMs());
                throw new RateLimitExceededException(rl);
            }
        }

        // === Bước 4: Circuit Breaker ===
        if (isCircuitBreakerOpen()) {
            log.warn("[Gateway] Circuit breaker OPEN — rejecting request: resourceId={}", resourceId);
            throw new FrameworkException(
                    FailureReason.SYSTEM_ERROR,
                    resourceId, null,
                    "Service tạm thời không khả dụng — circuit breaker đang mở. Vui lòng thử lại sau.");
        }

        // === Bước 5: Submit to SagaOrchestrator ===
        log.info("[Gateway] Submitting to saga: resourceId={}, idempotencyKey={}",
                resourceId, idempotencyKey);
        O order = orchestrator.process(request);

        // === Bước 6: Cache kết quả cho idempotency ===
        // Lưu orderId — nếu client retry với cùng key sẽ biết request đã được xử lý
        idempotencyHandler.markProcessed(idempotencyKey, order.getOrderId());

        log.info("[Gateway] Request processed successfully: orderId={}, status={}",
                order.getOrderId(), order.getStatus());
        return order;
    }

    // =========================================================================
    // DEVELOPER BẮT BUỘC IMPLEMENT
    // =========================================================================

    /**
     * Business-specific validation — chạy SAU basic field validation của framework.
     *
     * <p>Framework đảm bảo các field cơ bản (resourceId, quantity, idempotencyKey)
     * không null/blank trước khi gọi method này.
     *
     * <p>Trả về {@link ValidationResult#ok()} nếu không có lỗi.
     * Dùng {@link ValidationResult#fail} để mô tả lỗi, KHÔNG throw exception.
     *
     * @param request request đã qua basic validation
     * @return ValidationResult với business errors (nếu có)
     */
    protected abstract ValidationResult validateBusinessRules(REQ request);

    // =========================================================================
    // DEVELOPER CÓ THỂ OVERRIDE (tùy chọn)
    // =========================================================================

    /**
     * Có rate limit request này không?
     * Override để disable rate limit cho admin, internal service, v.v.
     *
     * @param request request cần kiểm tra
     * @return true nếu cần rate limit (default: luôn true)
     */
    protected boolean shouldRateLimit(REQ request) {
        return true;
    }

    /**
     * Key dùng cho rate limiter bucket.
     * Override để dùng key khác (IP, composite key, v.v.)
     *
     * @param request request hiện tại
     * @return key phân biệt bucket (default: requesterId)
     */
    protected String getRateLimitKey(REQ request) {
        return request.getRequesterId();
    }

    /**
     * Key dùng cho idempotency check.
     * Override nếu muốn dùng key khác thay vì {@code request.getIdempotencyKey()}.
     *
     * @param request request hiện tại
     * @return idempotency key (default: request.getIdempotencyKey())
     */
    protected String getIdempotencyKey(REQ request) {
        return request.getIdempotencyKey();
    }

    /**
     * Kiểm tra Circuit Breaker có đang OPEN không.
     * Override để wire với {@link io.hrc.inventory.decorator.CircuitBreakerInventoryDecorator}:
     *
     * <pre>{@code
     * @Override
     * protected boolean isCircuitBreakerOpen() {
     *     return circuitBreaker.getState() == CircuitBreakerState.OPEN;
     * }
     * }</pre>
     *
     * @return true nếu CB đang OPEN và cần từ chối request (default: false — không có CB)
     */
    protected boolean isCircuitBreakerOpen() {
        return false;
    }

    // =========================================================================
    // Framework basic validation — không expose ra ngoài
    // =========================================================================

    private ValidationResult validateBasicFields(REQ request) {
        if (request == null) {
            return ValidationResult.fail("request", "request không được null");
        }

        ValidationResult result = ValidationResult.ok();

        if (request.getResourceId() == null || request.getResourceId().isBlank()) {
            result = result.merge(ValidationResult.fail("resourceId", "không được để trống"));
        }
        if (request.getRequesterId() == null || request.getRequesterId().isBlank()) {
            result = result.merge(ValidationResult.fail("requesterId", "không được để trống"));
        }
        if (request.getQuantity() <= 0) {
            result = result.merge(ValidationResult.fail("quantity",
                    "phải > 0", request.getQuantity()));
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            result = result.merge(ValidationResult.fail("idempotencyKey", "không được để trống"));
        }

        return result;
    }
}
