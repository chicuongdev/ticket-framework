package io.hrc.gateway.ratelimit;

/**
 * Ném khi request bị từ chối do vượt rate limit.
 *
 * <p>Exception này KHÔNG extend {@link io.hrc.core.exception.FrameworkException}
 * vì rate limiting không phải lỗi hệ thống — là hành vi bảo vệ bình thường.
 * Developer's exception handler map exception này → HTTP 429 Too Many Requests.
 *
 * <p>Ví dụ Spring exception handler:
 * <pre>{@code
 * @ExceptionHandler(RateLimitExceededException.class)
 * public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
 *     RateLimitResult rl = ex.getRateLimitResult();
 *     return ResponseEntity.status(429)
 *         .header("X-RateLimit-Remaining", String.valueOf(rl.getRemainingPermits()))
 *         .header("X-RateLimit-Reset", String.valueOf(rl.getResetAfterMs()))
 *         .header("X-RateLimit-Limit", String.valueOf(rl.getLimitPerSecond()))
 *         .body(new ErrorResponse("RATE_LIMIT_EXCEEDED", ex.getMessage()));
 * }
 * }</pre>
 */
public class RateLimitExceededException extends RuntimeException {

    private final RateLimitResult rateLimitResult;

    public RateLimitExceededException(RateLimitResult result) {
        super(String.format(
            "Rate limit exceeded — remaining=%d permits, retry after %dms",
            result.getRemainingPermits(),
            result.getResetAfterMs()));
        this.rateLimitResult = result;
    }

    /** Thông tin chi tiết về rate limit để set HTTP headers. */
    public RateLimitResult getRateLimitResult() {
        return rateLimitResult;
    }
}
