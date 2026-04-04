package io.hrc.inventory.event;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/** Published sau khi restock() thành công. */
@Getter
public class ResourceRestockedEvent extends DomainEvent {
    private final long quantityAdded;
    private final long newAvailable;

    public ResourceRestockedEvent(String resourceId, long quantityAdded,
                                   long newAvailable, String correlationId) {
        super(resourceId, null, correlationId);
        this.quantityAdded = quantityAdded;
        this.newAvailable = newAvailable;
    }
}
