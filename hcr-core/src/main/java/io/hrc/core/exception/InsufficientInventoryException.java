package io.hrc.core.exception;

import io.hrc.core.enums.FailureReason;

/**
 * Ném khi reserve thất bại do không đủ tồn kho.
 * Xảy ra ở Inventory module — tất cả 3 strategy đều có thể ném exception này.
 *
 * <p>Lưu ý: trong luồng bình thường, InventoryStrategy trả về
 * {@link io.hrc.core.result.ReservationResult#insufficient} thay vì throw.
 * Exception này chỉ được ném khi cần propagate ra ngoài async boundary.
 */
public class InsufficientInventoryException extends FrameworkException {

    private final int requestedQuantity;
    private final long availableQuantity;

    public InsufficientInventoryException(String resourceId, int requestedQuantity, long availableQuantity) {
        super(FailureReason.INSUFFICIENT_INVENTORY, resourceId, null,
            String.format("Không đủ tồn kho: yêu cầu %d, còn lại %d (resource: %s)",
                requestedQuantity, availableQuantity, resourceId));
        this.requestedQuantity = requestedQuantity;
        this.availableQuantity = availableQuantity;
    }

    public int getRequestedQuantity() {
        return requestedQuantity;
    }

    public long getAvailableQuantity() {
        return availableQuantity;
    }
}
