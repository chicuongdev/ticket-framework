package io.hrc.core.domain;

import io.hrc.core.enums.FailureReason;
import io.hrc.core.enums.OrderStatus;

/**
 * Bridge cho phep framework (Saga module) goi cac method package-private
 * cua {@link AbstractOrder} tu package khac.
 *
 * <p><b>CHI DANH CHO FRAMEWORK INTERNAL.</b> Developer KHONG su dung class nay.
 *
 * <p>Ly do ton tai: {@code transitionTo()} va {@code markFailedWith()} la
 * package-private de developer khong vo tinh goi truc tiep. Nhung Saga
 * Orchestrator nam o package {@code io.hrc.saga} — can mot cau noi.
 */
public final class OrderAccessor {

    private OrderAccessor() {
    }

    /**
     * Chuyen trang thai order voi validation state machine.
     *
     * @throws IllegalStateException neu transition khong hop le
     */
    public static void transitionTo(AbstractOrder order, OrderStatus next) {
        order.transitionTo(next);
    }

    /**
     * Gan failure reason cho order.
     */
    public static void markFailedWith(AbstractOrder order, FailureReason reason) {
        order.markFailedWith(reason);
    }
}
