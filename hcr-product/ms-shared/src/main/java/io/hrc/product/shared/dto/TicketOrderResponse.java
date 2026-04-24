package io.hrc.product.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Response cho POST /orders và GET /orders/{id}.
 *
 * <p>Async flow trả HTTP 202 với status=RESERVED ngay sau khi reserve thành công;
 * client poll GET /orders/{id} để biết kết quả CONFIRMED hay CANCELLED.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TicketOrderResponse {

    private String orderId;
    private String resourceId;
    private String requesterId;
    private int quantity;
    private String status;
    private BigDecimal totalAmount;
    private String currency;
    private Instant createdAt;
    private Instant expiresAt;
    private String failureReason;
}
