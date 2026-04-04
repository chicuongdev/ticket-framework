package io.hrc.payment.model;

import lombok.Getter;

import java.time.Instant;

/**
 * Kết quả trả về từ thao tác pre-authorize (giữ tiền trước, charge sau).
 *
 * <p>Use case điển hình: đặt phòng khách sạn — giữ tiền khi check-in,
 * capture khi check-out, void nếu hủy đặt phòng.
 *
 * <pre>{@code
 * AuthorizationResult auth = gateway.preAuthorize(request);
 * if (auth.isAuthorized()) {
 *     // tiền đã được giữ → lưu authorizationId để capture sau
 *     String authId = auth.getAuthorizationId();
 * }
 * }</pre>
 */
@Getter
public class AuthorizationResult {

    public enum Status {
        /** Tiền đã được giữ thành công. */
        AUTHORIZED,
        /** Gateway từ chối giữ tiền. */
        DECLINED,
        /** Không rõ kết quả. */
        UNKNOWN
    }

    private final Status status;

    /** ID authorization phía gateway — dùng để capture hoặc void sau này. */
    private final String authorizationId;

    private final String transactionId;
    private final long authorizedAmount;
    private final Instant authorizedAt;

    /** Thời điểm authorization hết hạn (gateway quy định). */
    private final Instant expiresAt;

    private final String errorCode;
    private final String errorMessage;

    private AuthorizationResult(Status status, String authorizationId, String transactionId,
                                long authorizedAmount, Instant authorizedAt, Instant expiresAt,
                                String errorCode, String errorMessage) {
        this.status = status;
        this.authorizationId = authorizationId;
        this.transactionId = transactionId;
        this.authorizedAmount = authorizedAmount;
        this.authorizedAt = authorizedAt;
        this.expiresAt = expiresAt;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static AuthorizationResult authorized(String authorizationId, String transactionId,
                                                  long amount, Instant expiresAt) {
        return new AuthorizationResult(
                Status.AUTHORIZED, authorizationId, transactionId,
                amount, Instant.now(), expiresAt, null, null
        );
    }

    public static AuthorizationResult declined(String transactionId, String errorCode, String errorMessage) {
        return new AuthorizationResult(
                Status.DECLINED, null, transactionId,
                0, null, null, errorCode, errorMessage
        );
    }

    public static AuthorizationResult unknown(String transactionId) {
        return new AuthorizationResult(
                Status.UNKNOWN, null, transactionId,
                0, null, null, null, null
        );
    }

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    public boolean isAuthorized() {
        return status == Status.AUTHORIZED;
    }

    public boolean isDeclined() {
        return status == Status.DECLINED;
    }

    public boolean isUnknown() {
        return status == Status.UNKNOWN;
    }
}
