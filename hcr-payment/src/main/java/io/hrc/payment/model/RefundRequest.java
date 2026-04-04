package io.hrc.payment.model;

import lombok.Builder;
import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Input cho thao tác refund (hoàn tiền).
 *
 * <p>Dùng khi Saga cần compensate: cancel order → refund payment.
 *
 * <pre>{@code
 * RefundRequest request = RefundRequest.builder()
 *     .transactionId("tx-001")
 *     .gatewayTransactionId("gw-abc-123")
 *     .amount(150_000L)
 *     .reason("Order cancelled by user")
 *     .build();
 * }</pre>
 */
@Getter
@Builder
public class RefundRequest {

    /** ID giao dịch gốc phía chúng ta. */
    private final String transactionId;

    /** ID giao dịch gốc phía gateway — bắt buộc để gateway biết refund cho giao dịch nào. */
    private final String gatewayTransactionId;

    /** Số tiền refund. Nếu == amount gốc → full refund, < amount gốc → partial refund. */
    private final long amount;

    /** Lý do refund — ghi log và gửi sang gateway. */
    private final String reason;

    /** Metadata bổ sung. */
    @Builder.Default
    private final Map<String, String> metadata = new HashMap<>();

    public Map<String, String> getMetadata() {
        return Collections.unmodifiableMap(metadata);
    }
}
