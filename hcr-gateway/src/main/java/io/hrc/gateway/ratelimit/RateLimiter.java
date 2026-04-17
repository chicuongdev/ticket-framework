package io.hrc.gateway.ratelimit;

/**
 * Contract cho rate limiting — giới hạn số request trong một khoảng thời gian.
 *
 * <p>Framework cung cấp {@link io.hrc.gateway.ratelimit.redis.RedisTokenBucketRateLimiter}
 * dùng Token Bucket algorithm trên Redis. Developer có thể implement custom
 * nếu cần thuật toán hoặc backend khác (ví dụ: sliding window, in-memory).
 *
 * <p><b>Key là gì?</b> Key phân biệt "bucket" riêng biệt. Thường là:
 * <ul>
 *   <li>{@code requesterId} — limit per user</li>
 *   <li>{@code resourceId} — limit per resource</li>
 *   <li>{@code requesterId + ":" + resourceId} — limit per user per resource</li>
 *   <li>IP address — limit per IP (chống abuse)</li>
 * </ul>
 *
 * <p>Developer override {@code FrameworkGateway.getRateLimitKey(request)} để
 * chọn key phù hợp với nghiệp vụ.
 */
public interface RateLimiter {

    /**
     * Thử lấy 1 permit cho key.
     *
     * @param key key phân biệt bucket (ví dụ: userId)
     * @return true nếu được phép, false nếu vượt limit
     */
    boolean tryAcquire(String key);

    /**
     * Thử lấy {@code permits} permit cho key.
     *
     * @param key     key phân biệt bucket
     * @param permits số permit cần lấy
     * @return true nếu được phép, false nếu vượt limit
     */
    boolean tryAcquire(String key, int permits);

    /**
     * Thử lấy 1 permit và trả về thông tin chi tiết.
     * Dùng khi cần set HTTP headers ({@code X-RateLimit-Remaining}, ...).
     *
     * @param key key phân biệt bucket
     * @return {@link RateLimitResult} với đầy đủ thông tin về trạng thái bucket
     */
    RateLimitResult tryAcquireWithInfo(String key);

    /**
     * Cấu hình limit riêng cho một key cụ thể.
     * Override default config được set trong constructor.
     *
     * <p>Ví dụ dùng: VIP user có limit cao hơn, resource hot có limit thấp hơn.
     *
     * @param key              key cần config
     * @param permitsPerSecond số request được phép mỗi giây
     * @param burstCapacity    số request tối đa có thể xử lý cùng lúc (burst)
     */
    void configure(String key, long permitsPerSecond, long burstCapacity);
}
