package io.hrc.gateway.ratelimit;

import lombok.Getter;

/**
 * Kết quả chi tiết của một rate limit check.
 *
 * <p>Cung cấp đủ thông tin để client hiểu tình trạng và biết
 * khi nào có thể retry:
 * <ul>
 *   <li>{@link #allowed} — có được phép tiếp tục không</li>
 *   <li>{@link #remainingPermits} — còn bao nhiêu permit trong window</li>
 *   <li>{@link #resetAfterMs} — bao lâu nữa thì bucket đầy trở lại</li>
 *   <li>{@link #limitPerSecond} — giới hạn đã config (để client hiển thị)</li>
 * </ul>
 *
 * <p>Thường được dùng để set HTTP headers:
 * <pre>
 * X-RateLimit-Remaining: {remainingPermits}
 * X-RateLimit-Reset:     {resetAfterMs}
 * X-RateLimit-Limit:     {limitPerSecond}
 * </pre>
 */
@Getter
public class RateLimitResult {

    /** Có được phép gửi request không. */
    private final boolean allowed;

    /** Số permit còn lại trong window hiện tại. */
    private final long remainingPermits;

    /**
     * Sau bao nhiêu ms thì bucket được refill đến capacity.
     * Client dùng giá trị này để biết khi nào có thể retry an toàn.
     */
    private final long resetAfterMs;

    /** Số request được phép mỗi giây — dùng để hiển thị cho client. */
    private final long limitPerSecond;

    private RateLimitResult(boolean allowed, long remainingPermits,
                            long resetAfterMs, long limitPerSecond) {
        this.allowed = allowed;
        this.remainingPermits = remainingPermits;
        this.resetAfterMs = resetAfterMs;
        this.limitPerSecond = limitPerSecond;
    }

    /**
     * Tạo result khi request được chấp nhận.
     *
     * @param remainingPermits số permit còn lại sau khi tiêu
     * @param resetAfterMs     ms cho đến khi bucket đầy
     * @param limitPerSecond   giới hạn đã config
     */
    public static RateLimitResult allowed(long remainingPermits,
                                          long resetAfterMs,
                                          long limitPerSecond) {
        return new RateLimitResult(true, remainingPermits, resetAfterMs, limitPerSecond);
    }

    /**
     * Tạo result khi request bị từ chối do vượt rate limit.
     *
     * @param remainingPermits số permit hiện có (thường là 0)
     * @param resetAfterMs     ms cho đến khi có thể retry
     * @param limitPerSecond   giới hạn đã config
     */
    public static RateLimitResult denied(long remainingPermits,
                                         long resetAfterMs,
                                         long limitPerSecond) {
        return new RateLimitResult(false, remainingPermits, resetAfterMs, limitPerSecond);
    }
}
