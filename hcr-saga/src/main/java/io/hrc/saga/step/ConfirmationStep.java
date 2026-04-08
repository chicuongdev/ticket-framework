package io.hrc.saga.step;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.event.order.OrderConfirmedEvent;
import io.hrc.saga.context.SagaContext;
import lombok.extern.slf4j.Slf4j;

/**
 * Step 3 (cuoi cung) trong Saga — xac nhan order va publish event.
 *
 * <p><b>Execute:</b> publish {@link OrderConfirmedEvent} len EventBus.
 * State transition (RESERVED → CONFIRMED) va save order do orchestrator
 * xu ly TRUOC khi goi step nay.
 *
 * <p><b>Compensate:</b> No-op — day la step cuoi cung, khong the hoan tac
 * confirmation da publish. Neu can cancel sau confirm, dung
 * {@code adminCancel()}.
 *
 * @param <O> kieu order cu the cua developer
 */
@Slf4j
public class ConfirmationStep<O extends AbstractOrder> implements SagaStep<O> {

    private final EventBus eventBus;

    public ConfirmationStep(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public StepResult execute(SagaContext<O> context) {
        O order = context.getOrder();

        eventBus.publish(new OrderConfirmedEvent(
                order.getResourceId(),
                order.getOrderId(),
                order.getRequesterId(),
                order.getQuantity(),
                context.getCorrelationId()));

        log.debug("[Saga] Confirmation published: orderId={}", order.getOrderId());
        return StepResult.success();
    }

    @Override
    public void compensate(SagaContext<O> context) {
        // No-op — final step, không có compensate
    }

    @Override
    public String getStepName() {
        return "confirmation";
    }

    @Override
    public boolean isRetryable() {
        return false;
    }
}
