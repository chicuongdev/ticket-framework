package io.hrc.core.exception;

import io.hrc.core.enums.FailureReason;

/**
 * Ném khi phát hiện duplicate request qua idempotency key.
 * Được ném bởi Gateway module khi idempotencyKey đã được xử lý trong 24h gần nhất.
 *
 * <p>Đây không phải lỗi hệ thống — là behavior bình thường khi client retry.
 * Framework trả về cached result của request trước đó thay vì xử lý lại.
 */
public class IdempotencyException extends FrameworkException {

    private final String idempotencyKey;

    public IdempotencyException(String idempotencyKey) {
        super(FailureReason.DUPLICATE_REQUEST, null, null,
            String.format("Request đã được xử lý trước đó: idempotencyKey=%s", idempotencyKey));
        this.idempotencyKey = idempotencyKey;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }
}
