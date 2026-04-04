package io.hrc.eventbus.event.reconciliation;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

import java.time.Instant;

/**
 * Publish khi ReconciliationService bắt đầu một cycle kiểm tra.
 *
 * <p>Dùng để monitoring: track tần suất reconciliation, phát hiện nếu
 * reconciliation đột nhiên dừng chạy.
 */
@Getter
public class ReconciliationStartedEvent extends DomainEvent {

    /** Loại reconciliation đang chạy (inventory, payment, expired-orders...). */
    private final String reconciliationType;

    /** Số lượng item sẽ được check trong cycle này. */
    private final int itemCount;

    /** Thời điểm cycle bắt đầu. */
    private final Instant startedAt;

    public ReconciliationStartedEvent(String reconciliationType, int itemCount,
                                       String correlationId) {
        super(null, null, correlationId);
        this.reconciliationType = reconciliationType;
        this.itemCount          = itemCount;
        this.startedAt          = Instant.now();
    }
}
