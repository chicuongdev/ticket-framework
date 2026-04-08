package io.hrc.saga.step;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.saga.context.SagaContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 1 trong Saga — giu cho inventory.
 *
 * <p><b>Execute:</b> goi {@link InventoryStrategy#reserve(String, String, int)}.
 * <br><b>Compensate:</b> goi {@link InventoryStrategy#release(String, String, int)}.
 *
 * <p>Compensate KHONG throw exception — neu release fail, log error va
 * de {@code ReconciliationService} xu ly sau (≤ 5 phut).
 *
 * @param <O> kieu order cu the cua developer
 */
@Slf4j
public class ReservationStep<O extends AbstractOrder> implements SagaStep<O> {

    private final InventoryStrategy inventoryStrategy;

    public ReservationStep(InventoryStrategy inventoryStrategy) {
        this.inventoryStrategy = inventoryStrategy;
    }

    @Override
    public StepResult execute(SagaContext<O> context) {
        O order = context.getOrder();
        ReservationResult result = inventoryStrategy.reserve(
                order.getResourceId(), order.getOrderId(), order.getQuantity());
        context.setReservationResult(result);

        if (result.isSuccess()) {
            log.debug("[Saga] Reserve OK: orderId={}, remaining={}",
                    order.getOrderId(), result.getRemainingAfter());
            return StepResult.success();
        }
        if (result.isInsufficient()) {
            log.info("[Saga] Reserve insufficient: orderId={}, resourceId={}",
                    order.getOrderId(), order.getResourceId());
            return StepResult.failed(FailureReason.INSUFFICIENT_INVENTORY,
                    "Không đủ tồn kho cho resourceId=" + order.getResourceId());
        }
        log.error("[Saga] Reserve error: orderId={}, error={}",
                order.getOrderId(), result.getErrorMessage());
        return StepResult.failed(FailureReason.SYSTEM_ERROR, result.getErrorMessage());
    }

    @Override
    public void compensate(SagaContext<O> context) {
        O order = context.getOrder();
        try {
            inventoryStrategy.release(
                    order.getResourceId(), order.getOrderId(), order.getQuantity());
            log.debug("[Saga] Release OK (compensate): orderId={}", order.getOrderId());
        } catch (Exception e) {
            log.error("[Saga] Release failed (compensate), Reconciliation will fix: orderId={}",
                    order.getOrderId(), e);
        }
    }

    @Override
    public String getStepName() {
        return "reservation";
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
