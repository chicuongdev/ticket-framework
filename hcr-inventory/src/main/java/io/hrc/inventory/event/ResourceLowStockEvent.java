package io.hrc.inventory.event;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/** Published khi availableQuantity xuống dưới ngưỡng lowStockThreshold. */
@Getter
public class ResourceLowStockEvent extends DomainEvent {
    private final long availableQuantity;
    private final long threshold;

    public ResourceLowStockEvent(String resourceId, long availableQuantity,
                                  long threshold, String correlationId) {
        super(resourceId, null, correlationId);
        this.availableQuantity = availableQuantity;
        this.threshold = threshold;
    }
}
