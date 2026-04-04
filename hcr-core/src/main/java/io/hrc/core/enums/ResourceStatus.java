package io.hrc.core.enums;

/**
 * Trạng thái của tài nguyên trong hệ thống.
 * Mô tả khả năng bán của tài nguyên tại một thời điểm.
 */
public enum ResourceStatus {

    /** Đang bán bình thường — trạng thái mặc định khi initialize. */
    ACTIVE,

    /** Sắp hết hàng — số lượng còn lại dưới ngưỡng threshold. Framework tự detect. */
    LOW_STOCK,

    /** Hết hàng hoàn toàn — mọi request reserve sẽ bị từ chối. Framework tự detect. */
    DEPLETED,

    /** Ngừng bán theo yêu cầu admin — developer gọi deactivate(). */
    DEACTIVATED;

    /** Kiểm tra tài nguyên có đang nhận đặt hàng không. */
    public boolean isAcceptingOrders() {
        return this == ACTIVE || this == LOW_STOCK;
    }
}
