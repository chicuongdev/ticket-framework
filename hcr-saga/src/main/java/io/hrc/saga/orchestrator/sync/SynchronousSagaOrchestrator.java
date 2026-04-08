package io.hrc.saga.orchestrator.sync;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.domain.OrderAccessor;
import io.hrc.core.domain.OrderRequest;
import io.hrc.core.enums.OrderStatus;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.saga.context.SagaContext;
import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;
import io.hrc.saga.repository.SagaStateRepository;
import io.hrc.saga.step.StepResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Saga dong bo — dung cho P1 (Pessimistic Lock) va P2 (Optimistic Lock).
 *
 * <p><b>Flow:</b>
 * <ol>
 *   <li>Validate + create order (PENDING)</li>
 *   <li>Save order to DB</li>
 *   <li>Reserve inventory (DB — blocking)</li>
 *   <li>Transition PENDING → RESERVED, save</li>
 *   <li>Charge payment (blocking, co timeout handler)</li>
 *   <li>Transition RESERVED → CONFIRMED, save</li>
 *   <li>Publish OrderConfirmedEvent</li>
 *   <li>Return order — HTTP 201</li>
 * </ol>
 *
 * <p>Neu bat ky buoc nao fail → compensate cac buoc truoc → cancel order.
 *
 * <p><b>SagaStateRepository:</b> optional cho sync mode. Crash → client retry
 * → tao Saga moi → OK.
 *
 * @param <REQ> kieu request
 * @param <O>   kieu order
 */
@Slf4j
public abstract class SynchronousSagaOrchestrator<
        REQ extends OrderRequest,
        O extends AbstractOrder>
        extends AbstractSagaOrchestrator<REQ, O> {

    protected SynchronousSagaOrchestrator(InventoryStrategy inventoryStrategy,
                                           PaymentGateway paymentGateway,
                                           EventBus eventBus) {
        super(inventoryStrategy, paymentGateway, eventBus, null);
    }

    protected SynchronousSagaOrchestrator(InventoryStrategy inventoryStrategy,
                                           PaymentGateway paymentGateway,
                                           EventBus eventBus,
                                           SagaStateRepository<O> sagaStateRepository) {
        super(inventoryStrategy, paymentGateway, eventBus, sagaStateRepository);
    }

    @Override
    protected O executeFlow(SagaContext<O> context) {
        O order = context.getOrder();

        // Save order PENDING to DB
        order = saveOrder(order);
        context.setOrder(order);

        // === Step 1: Reserve ===
        onReserving(order);
        StepResult reserveResult = getReservationStep().execute(context);
        if (!reserveResult.isSuccess()) {
            context.markStepFailed("reservation");
            return cancelOrder(context, reserveResult.getFailureReason(),
                    reserveResult.getErrorMessage());
        }
        context.markStepCompleted("reservation");

        // Transition PENDING → RESERVED
        OrderAccessor.transitionTo(order, OrderStatus.RESERVED);
        order = saveOrder(order);
        context.setOrder(order);

        // === Step 2 + 3: Payment + Confirmation ===
        return executePaymentAndConfirmation(context);
    }
}
