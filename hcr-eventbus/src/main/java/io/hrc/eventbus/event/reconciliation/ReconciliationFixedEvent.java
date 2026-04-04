package io.hrc.eventbus.event.reconciliation;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/**
 * Publish sau khi ReconciliationService fix xong một case bất đồng bộ.
 *
 * <p>Dùng để audit và monitoring: biết những gì đã được tự động fix,
 * tần suất fix nói lên mức độ ổn định của hệ thống.
 */
@Getter
public class ReconciliationFixedEvent extends DomainEvent {

    /** Loại fix đã thực hiện (release-inventory, cancel-order, sync-redis...). */
    private final String fixType;

    /** Mô tả chi tiết những gì đã được fix. */
    private final String description;

    public ReconciliationFixedEvent(String resourceId, String orderId,
                                     String fixType, String description,
                                     String correlationId) {
        super(resourceId, orderId, correlationId);
        this.fixType     = fixType;
        this.description = description;
    }
}
