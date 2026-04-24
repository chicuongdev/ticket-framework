package io.hrc.product.shared.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body cho POST /orders trên ms-order.
 *
 * <p>Validate: resourceId không blank, requesterId không blank, quantity ≥ 1,
 * idempotencyKey không blank (chống duplicate request).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlaceTicketOrderRequest {

    @NotBlank
    private String resourceId;

    @NotBlank
    private String requesterId;

    @Min(1)
    private int quantity;

    @NotBlank
    private String idempotencyKey;
}
