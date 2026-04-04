package io.hrc.eventbus.event.reconciliation;

import io.hrc.core.domain.DomainEvent;
import lombok.Getter;

/**
 * Publish khi InventoryReconciler phát hiện lệch giữa Redis và DB.
 *
 * <p>Chỉ có ý nghĩa khi dùng P3 (RedisAtomicStrategy).
 * Sau khi event này được publish, ReconciliationService sẽ fix mismatch
 * và publish {@link ReconciliationFixedEvent}.
 *
 * <p>Nếu event này xuất hiện thường xuyên → cần điều tra:
 * P3 consumer (InventoryPersistenceConsumer) có đang fail không?
 */
@Getter
public class InventoryMismatchEvent extends DomainEvent {

    /** Giá trị available trong Redis. */
    private final long redisAvailable;

    /** Giá trị available trong DB. */
    private final long dbAvailable;

    /** Chênh lệch = redisAvailable - dbAvailable. */
    private final long delta;

    public InventoryMismatchEvent(String resourceId,
                                   long redisAvailable, long dbAvailable,
                                   String correlationId) {
        super(resourceId, null, correlationId);
        this.redisAvailable = redisAvailable;
        this.dbAvailable    = dbAvailable;
        this.delta          = redisAvailable - dbAvailable;
    }
}
