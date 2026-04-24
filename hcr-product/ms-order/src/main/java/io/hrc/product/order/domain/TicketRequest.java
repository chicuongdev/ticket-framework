package io.hrc.product.order.domain;

import io.hrc.core.domain.OrderRequest;

public class TicketRequest extends OrderRequest {

    public TicketRequest(String resourceId, String requesterId,
                         int quantity, String idempotencyKey) {
        super(resourceId, requesterId, quantity, idempotencyKey);
    }
}
