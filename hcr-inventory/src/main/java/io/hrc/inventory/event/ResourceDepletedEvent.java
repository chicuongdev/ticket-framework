package io.hrc.inventory.event;

import io.hrc.core.domain.DomainEvent;

/** Published khi availableQuantity chạm 0. Framework tự detect và publish. */
public class ResourceDepletedEvent extends DomainEvent {
    public ResourceDepletedEvent(String resourceId, String correlationId) {
        super(resourceId, null, correlationId);
    }
}
