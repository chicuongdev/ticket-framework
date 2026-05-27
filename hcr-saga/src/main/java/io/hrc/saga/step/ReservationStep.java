package io.hrc.saga.step;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.result.ReservationResult;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.saga.context.SagaContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

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

        // Retry với exponential backoff. release() có thể fail vì:
        //   - StaleObjectStateException (Hibernate version interference — đã fix bằng native SQL nhưng giữ defense)
        //   - PessimisticLockException (lock timeout)
        //   - DataAccessException (DB connection blip, deadlock)
        // Tổng thời gian tối đa: 50+200+500 = 750ms — chấp nhận được cho path 4xx/5xx.
        long[] backoffMs = {50L, 200L, 500L};
        Exception lastError = null;

        for (int attempt = 0; attempt < backoffMs.length; attempt++) {
            try {
                inventoryStrategy.release(
                        order.getResourceId(), order.getOrderId(), order.getQuantity());
                order.setInventoryReleasedAt(Instant.now());   // ĐÁNH DẤU release thành công
                log.debug("[Saga] Release OK (compensate, attempt={}): orderId={}",
                        attempt + 1, order.getOrderId());
                return;
            } catch (Exception e) {
                lastError = e;
                log.warn("[Saga] Release failed attempt {}/{}, retrying in {}ms: orderId={}, cause={}",
                        attempt + 1, backoffMs.length, backoffMs[attempt],
                        order.getOrderId(), e.getMessage());
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // Hết retry — giữ inventoryReleasedAt=null để Reconciliation Case 6 (ORPHAN_CANCELLED) nhặt rác.
        // Saga tiếp tục transition order → CANCELLED bình thường; reconciliation sẽ retry release sau.
        log.error("[Saga] Release FAILED after {} retries — orphan flagged, Reconciliation Case 6 will fix: orderId={}",
                backoffMs.length, order.getOrderId(), lastError);
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
