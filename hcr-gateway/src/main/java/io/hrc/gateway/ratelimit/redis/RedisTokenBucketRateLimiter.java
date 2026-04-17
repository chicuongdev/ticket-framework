package io.hrc.gateway.ratelimit.redis;

import io.hrc.gateway.ratelimit.RateLimitResult;
import io.hrc.gateway.ratelimit.RateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token Bucket Rate Limiter dùng Redis — thread-safe nhờ Lua script atomic.
 *
 * <p><b>Thuật toán Token Bucket:</b>
 * <pre>
 * Mỗi "bucket" (per key) có:
 *   capacity:   số token tối đa (= burstCapacity)
 *   refillRate: số token thêm mỗi giây (= permitsPerSecond)
 *
 * Mỗi request tiêu 1 token:
 *   → Nếu còn token: cho qua + trừ 1 token
 *   → Nếu hết token: từ chối (HTTP 429)
 *
 * Token được refill tự động theo thời gian thực.
 * VD: 100 rps, burst 200 → user có thể gửi 200 req đồng loạt,
 *     sau đó max 100 req/s.
 * </pre>
 *
 * <p><b>Redis key layout (per key):</b>
 * <pre>
 * hcr:ratelimit:{key}:tokens — số token hiện có
 * hcr:ratelimit:{key}:ts     — timestamp lần cuối cập nhật (ms)
 * </pre>
 *
 * <p><b>Per-key config:</b> {@link #configure(String, long, long)} để set limit
 * khác nhau cho từng user/resource. Default dùng giá trị từ constructor.
 *
 * <p><b>Lua script đảm bảo atomicity:</b> GET + compute + SET là 1 operation
 * trên Redis → không có race condition giữa các instance.
 */
@Slf4j
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "hcr:ratelimit:";

    private final RedisTemplate<String, String> redisTemplate;
    private final long defaultPermitsPerSecond;
    private final long defaultBurstCapacity;

    /** Per-key override config: key → [permitsPerSecond, burstCapacity]. */
    private final ConcurrentHashMap<String, long[]> perKeyConfig = new ConcurrentHashMap<>();

    /** Lua script — load một lần, cache lại (SHA hash). */
    private final DefaultRedisScript<List<Long>> tokenBucketScript;

    /**
     * @param redisTemplate          Spring RedisTemplate (String, String)
     * @param defaultPermitsPerSecond số request được phép mỗi giây (default cho mọi key)
     * @param defaultBurstCapacity    số request tối đa burst (default cho mọi key)
     */
    @SuppressWarnings("unchecked")
    public RedisTokenBucketRateLimiter(RedisTemplate<String, String> redisTemplate,
                                       long defaultPermitsPerSecond,
                                       long defaultBurstCapacity) {
        this.redisTemplate = redisTemplate;
        this.defaultPermitsPerSecond = defaultPermitsPerSecond;
        this.defaultBurstCapacity = defaultBurstCapacity;

        this.tokenBucketScript = new DefaultRedisScript<>();
        this.tokenBucketScript.setLocation(new ClassPathResource("lua/rate_limit_token_bucket.lua"));
        this.tokenBucketScript.setResultType((Class<List<Long>>) (Class<?>) List.class);
    }

    @Override
    public boolean tryAcquire(String key) {
        return tryAcquireWithInfo(key).isAllowed();
    }

    @Override
    public boolean tryAcquire(String key, int permits) {
        long[] config = getConfig(key);
        List<Long> result = executeScript(key, permits, config[0], config[1]);
        return result != null && !result.isEmpty() && result.get(0) == 1L;
    }

    @Override
    public RateLimitResult tryAcquireWithInfo(String key) {
        long[] config = getConfig(key);
        List<Long> result = executeScript(key, 1, config[0], config[1]);

        if (result == null || result.isEmpty()) {
            // Lỗi Redis — fail open (cho qua) để tránh block toàn bộ traffic
            log.warn("[RateLimit] Redis script returned null for key={}, failing open", key);
            return RateLimitResult.allowed(config[1], 0, config[0]);
        }

        boolean allowed       = result.get(0) == 1L;
        long remainingPermits = result.get(1);
        long resetAfterMs     = result.get(2);
        long limitPerSecond   = result.get(3);

        if (!allowed) {
            log.debug("[RateLimit] Denied: key={}, remaining={}, resetAfterMs={}",
                    key, remainingPermits, resetAfterMs);
        }

        return allowed
                ? RateLimitResult.allowed(remainingPermits, resetAfterMs, limitPerSecond)
                : RateLimitResult.denied(remainingPermits, resetAfterMs, limitPerSecond);
    }

    @Override
    public void configure(String key, long permitsPerSecond, long burstCapacity) {
        perKeyConfig.put(key, new long[]{permitsPerSecond, burstCapacity});
        log.debug("[RateLimit] Configured: key={}, rps={}, burst={}", key, permitsPerSecond, burstCapacity);
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private List<Long> executeScript(String key, int requested, long rate, long capacity) {
        List<String> keys = List.of(
                KEY_PREFIX + key + ":tokens",
                KEY_PREFIX + key + ":ts"
        );
        return redisTemplate.execute(
                tokenBucketScript,
                keys,
                String.valueOf(requested),
                String.valueOf(rate),
                String.valueOf(capacity),
                String.valueOf(System.currentTimeMillis())
        );
    }

    /** Lấy config cho key — per-key nếu đã configure, default nếu chưa. */
    private long[] getConfig(String key) {
        return perKeyConfig.getOrDefault(key,
                new long[]{defaultPermitsPerSecond, defaultBurstCapacity});
    }
}
