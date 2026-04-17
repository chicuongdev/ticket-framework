package io.hrc.gateway.idempotency.redis;

import io.hrc.gateway.idempotency.IdempotencyHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * IdempotencyHandler dùng Redis với TTL.
 *
 * <p><b>Redis key layout:</b>
 * <pre>
 * hcr:idempotency:{key} → value (kết quả đã cache, thường là orderId)
 * </pre>
 *
 * <p><b>Cơ chế:</b>
 * <pre>
 * markProcessed(key, result):
 *   SET "hcr:idempotency:{key}" {result.toString()} EX {ttlSeconds}
 *
 * isDuplicate(key):
 *   EXISTS "hcr:idempotency:{key}"
 *   → true: đã xử lý
 *   → false: chưa xử lý
 *
 * getCachedResult(key):
 *   GET "hcr:idempotency:{key}"
 *   → Optional của value đã lưu
 * </pre>
 *
 * <p><b>TTL mặc định: 86400 giây (24 giờ)</b> — đủ dài để cover
 * trường hợp client retry sau network failure. Developer có thể
 * điều chỉnh qua constructor.
 *
 * <p><b>Lưu ý về value:</b> Framework lưu {@code result.toString()} —
 * thường là orderId (String). Nếu cần lưu object phức tạp hơn,
 * serialize thủ công trước khi gọi {@link #markProcessed}.
 */
@Slf4j
public class RedisIdempotencyHandler implements IdempotencyHandler {

    private static final String KEY_PREFIX = "hcr:idempotency:";
    static final long DEFAULT_TTL_SECONDS = 86_400L; // 24 giờ

    private final RedisTemplate<String, String> redisTemplate;
    private final long ttlSeconds;

    /**
     * Constructor với TTL mặc định 24 giờ.
     *
     * @param redisTemplate Spring RedisTemplate (String, String)
     */
    public RedisIdempotencyHandler(RedisTemplate<String, String> redisTemplate) {
        this(redisTemplate, DEFAULT_TTL_SECONDS);
    }

    /**
     * Constructor với TTL tùy chỉnh.
     *
     * @param redisTemplate Spring RedisTemplate (String, String)
     * @param ttlSeconds    thời gian tồn tại của cache (giây)
     */
    public RedisIdempotencyHandler(RedisTemplate<String, String> redisTemplate,
                                   long ttlSeconds) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
    }

    @Override
    public boolean isDuplicate(String key) {
        Boolean exists = redisTemplate.hasKey(KEY_PREFIX + key);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public void markProcessed(String key, Object result) {
        String value = (result != null) ? result.toString() : "";
        redisTemplate.opsForValue().set(KEY_PREFIX + key, value, ttlSeconds, TimeUnit.SECONDS);
        log.debug("[Idempotency] Marked processed: key={}, value={}", key, value);
    }

    @Override
    public Optional<Object> getCachedResult(String key) {
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + key);
        return Optional.ofNullable(value);
    }

    @Override
    public void expire(String key) {
        redisTemplate.delete(KEY_PREFIX + key);
        log.debug("[Idempotency] Expired: key={}", key);
    }
}
