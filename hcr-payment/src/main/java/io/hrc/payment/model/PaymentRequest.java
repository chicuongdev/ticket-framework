package io.hrc.payment.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Input chuẩn khi gọi payment gateway.
 *
 * <p>{@code transactionId} đóng vai trò idempotency key — thường bằng {@code orderId}.
 * Gateway dùng field này để đảm bảo không charge 2 lần cho cùng 1 giao dịch.
 *
 * <pre>{@code
 * PaymentRequest request = PaymentRequest.builder()
 *     .transactionId(order.getId())
 *     .amount(150_000L)
 *     .currency("VND")
 *     .description("Concert ticket - Sơn Tùng MTP")
 *     .build();
 * }</pre>
 */
@Getter
@Builder
public class PaymentRequest {

    /** ID giao dịch phía chúng ta — dùng làm idempotency key gửi sang gateway. */
    private final String transactionId;

    /** Số tiền (đơn vị nhỏ nhất: đồng cho VND, cent cho USD). */
    private final long amount;

    /** ISO 4217 currency code: "VND", "USD", "EUR"... */
    @Builder.Default
    private final String currency = "VND";

    /** Mô tả giao dịch hiển thị cho khách hàng. */
    private final String description;

    /** Developer thêm metadata tùy ý (vd: userId, productId...). */
    @Builder.Default
    private final Map<String, String> metadata = new HashMap<>();

    /**
     * Trả về metadata dạng unmodifiable — tránh mutation bên ngoài.
     */
    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
