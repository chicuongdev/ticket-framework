package io.hrc.inventory.event;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/**
 * Published sau khi reserve() thành công.
 * P3: Consumer nhận event này để đồng bộ DB.
 * P1/P2: Published async sau khi transaction commit (informational).
 */
@Getter
public class ResourceReservedEvent extends DomainEvent {
    private final String requestId;
    private final int quantity;
    private final long remainingAfter;

    public ResourceReservedEvent(String resourceId, String orderId, String requestId,
                                  int quantity, long remainingAfter, String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requestId = requestId;
        this.quantity = quantity;
        this.remainingAfter = remainingAfter;
    }
}
