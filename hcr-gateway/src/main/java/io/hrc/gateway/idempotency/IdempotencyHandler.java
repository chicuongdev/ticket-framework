package io.hrc.gateway.idempotency;

import java.util.Optional;

/**
 * Contract để đảm bảo cùng một request không bị xử lý hai lần.
 *
 * <p><b>Bài toán:</b> Client gửi request → timeout → client retry.
 * Nếu request đầu tiên đã được xử lý thành công, framework phải phát hiện
 * và không xử lý lại (tránh double booking, double charge).
 *
 * <p><b>Cơ chế:</b>
 * <ol>
 *   <li>Trước khi xử lý: {@link #isDuplicate(String)} kiểm tra key đã tồn tại chưa</li>
 *   <li>Nếu duplicate: ném {@link io.hrc.core.exception.IdempotencyException}</li>
 *   <li>Nếu mới: xử lý bình thường</li>
 *   <li>Sau khi xử lý thành công: {@link #markProcessed(String, Object)} lưu kết quả</li>
 * </ol>
 *
 * <p><b>Key là gì?</b> Key là giá trị {@code idempotencyKey} trong request.
 * Client phải sinh key này (UUID) và gửi cùng với mọi request.
 * Cùng một ý định = cùng key. Retry = cùng key. Yêu cầu mới = key mới.
 *
 * <p>Framework cung cấp {@link io.hrc.gateway.idempotency.redis.RedisIdempotencyHandler}
 * với TTL mặc định 24 giờ. Developer có thể implement custom backend.
 */
public interface IdempotencyHandler {

    /**
     * Kiểm tra key này đã được xử lý chưa.
     *
     * @param key idempotency key từ request
     * @return true nếu key đã tồn tại (request đã được xử lý)
     */
    boolean isDuplicate(String key);

    /**
     * Đánh dấu request đã được xử lý và lưu kết quả.
     * Gọi sau khi {@code orchestrator.process()} trả về thành công.
     *
     * <p>Trong framework, {@code result} thường là {@code orderId} (String)
     * của order vừa được tạo. Caller có thể truyền bất kỳ Object nào —
     * implementation lưu {@code result.toString()}.
     *
     * @param key    idempotency key
     * @param result kết quả cần lưu (thường là orderId)
     */
    void markProcessed(String key, Object result);

    /**
     * Lấy kết quả đã cache cho key.
     * Dùng để debug hoặc khi caller cần biết kết quả của request trước đó.
     *
     * @param key idempotency key
     * @return Optional chứa kết quả đã lưu, hoặc empty nếu không tìm thấy
     */
    Optional<Object> getCachedResult(String key);

    /**
     * Xóa cache thủ công cho key.
     * Dùng khi cần allow retry của request đã bị mark là duplicate
     * (ví dụ: xử lý trước đó fail, muốn cho phép thử lại).
     *
     * @param key idempotency key cần xóa
     */
    void expire(String key);
}
