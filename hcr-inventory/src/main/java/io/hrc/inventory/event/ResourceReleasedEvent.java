package io.hrc.inventory.event;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/** Published sau khi release() thành công (compensating action). */
@Getter
public class ResourceReleasedEvent extends DomainEvent {
    private final String requestId;
    private final int quantity;
    private final long remainingAfter;

    public ResourceReleasedEvent(String resourceId, String orderId, String requestId,
                                  int quantity, long remainingAfter, String correlationId) {
        super(resourceId, orderId, correlationId);
        this.requestId = requestId;
        this.quantity = quantity;
        this.remainingAfter = remainingAfter;
    }
}
