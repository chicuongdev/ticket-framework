package io.hrc.saga.orchestrator.async;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.domain.OrderAccessor;
import io.hrc.core.domain.OrderRequest;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.enums.OrderStatus;
import io.hrc.core.exception.FrameworkException;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.event.order.OrderCreatedEvent;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentResult;
import io.hrc.saga.context.SagaContext;
import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;
import io.hrc.saga.repository.SagaStateRepository;
import io.hrc.saga.step.StepResult;
import lombok.extern.slf4j.Slf4j;

/**
 * Saga bat dong bo — dung cho P3 (Redis Atomic Strategy).
 *
 * <p><b>Critical path (sync, nhanh — khong DB):</b>
 * <ol>
 *   <li>Validate</li>
 *   <li>Reserve inventory (Redis DECR atomic)</li>
 *   <li>Save saga state vao SagaStateRepository</li>
 *   <li>Publish {@link OrderCreatedEvent}</li>
 *   <li>Return order (RESERVED) — HTTP 202 ACCEPTED</li>
 * </ol>
 *
 * <p><b>Async path (qua EventBus consumers):</b>
 * <pre>
 * OrderCreatedEvent → PaymentConsumer → charge()
 * PaymentResult     → handlePaymentResult() → confirm/cancel
 * </pre>
 *
 * <p><b>SagaStateRepository: BAT BUOC.</b> Framework throw exception khi
 * khoi tao neu khong co. Crash giua Reserve va Payment → double charge risk
 * neu khong co state persisted.
 *
 * @param <REQ> kieu request
 * @param <O>   kieu order
 */
@Slf4j
public abstract class AsynchronousSagaOrchestrator<
        REQ extends OrderRequest,
        O extends AbstractOrder>
        extends AbstractSagaOrchestrator<REQ, O> {

    protected AsynchronousSagaOrchestrator(InventoryStrategy inventoryStrategy,
                                            PaymentGateway paymentGateway,
                                            EventBus eventBus,
                                            SagaStateRepository<O> sagaStateRepository) {
        super(inventoryStrategy, paymentGateway, eventBus, sagaStateRepository);
        if (sagaStateRepository == null) {
            throw new FrameworkException(
                    FailureReason.SYSTEM_ERROR, null, null,
                    "AsynchronousSaga requires a SagaStateRepository bean. " +
                    "Please implement SagaStateRepository<YourOrderType> and register " +
                    "it as a Spring bean.");
        }
    }

    // =========================================================================
    // Critical Path — sync, return nhanh
    // =========================================================================

    @Override
    protected O executeFlow(SagaContext<O> context) {
        O order = context.getOrder();

        // === Reserve (Redis — khong DB) ===
        onReserving(order);
        StepResult reserveResult = getReservationStep().execute(context);
        if (!reserveResult.isSuccess()) {
            context.markStepFailed("reservation");
            // Reserve fail → khong co gi de compensate, cancel nhe
            OrderAccessor.transitionTo(order, OrderStatus.CANCELLED);
            if (reserveResult.getFailureReason() != null) {
                OrderAccessor.markFailedWith(order, reserveResult.getFailureReason());
            }
            log.info("[Saga-Async] Reserve failed, cancelled: orderId={}, reason={}",
                    order.getOrderId(), reserveResult.getFailureReason());
            return order;
        }
        context.markStepCompleted("reservation");

        // Transition PENDING → RESERVED
        OrderAccessor.transitionTo(order, OrderStatus.RESERVED);

        // Save saga state (SagaStateRepository — co the la Redis, khong phai DB)
        sagaStateRepository.save(context);

        // Publish event cho async processing
        eventBus.publish(new OrderCreatedEvent(
                order.getResourceId(),
                order.getOrderId(),
                order.getRequesterId(),
                order.getQuantity(),
                context.getCorrelationId()));

        log.info("[Saga-Async] Reserved, event published: orderId={}", order.getOrderId());
        return order;
    }

    // =========================================================================
    // Async Consumers — goi tu EventBus handlers
    // =========================================================================

    /**
     * Xu ly ket qua payment — goi boi consumer sau khi PaymentGateway.charge() hoan tat.
     *
     * <p>Flow:
     * <ul>
     *   <li>Payment SUCCESS → transition CONFIRMED → save → publish OrderConfirmedEvent</li>
     *   <li>Payment FAILED/TIMEOUT/UNKNOWN → compensate reservation → cancel order</li>
     * </ul>
     *
     * @param orderId       ID order
     * @param paymentResult ket qua tu PaymentGateway
     * @return order voi status cuoi cung
     */
    public O handlePaymentResult(String orderId, PaymentResult paymentResult) {
        var sagaOpt = sagaStateRepository.findByOrderId(orderId);
        if (sagaOpt.isEmpty()) {
            throw new IllegalStateException(
                    "Saga state not found for orderId=" + orderId +
                    ". Order may have been expired or already processed.");
        }

        SagaContext<O> context = sagaOpt.get();
        O order = context.getOrder();
        context.setPaymentResult(paymentResult);

        if (paymentResult.isSuccess()) {
            context.markStepCompleted("payment");
            return handlePaymentSuccess(context);
        } else {
            context.markStepFailed("payment");
            FailureReason reason = mapPaymentFailure(paymentResult);
            return handlePaymentFailure(context, reason, paymentResult.getErrorMessage());
        }
    }

    /**
     * Developer override de xu ly custom logic khi nhan payment result.
     * Goi TRUOC khi framework xu ly confirm/cancel.
     */
    protected void onPaymentResultReceived(O order, PaymentResult result) {
    }

    // =========================================================================
    // Internal async handlers
    // =========================================================================

    private O handlePaymentSuccess(SagaContext<O> context) {
        O order = context.getOrder();
        onPaymentResultReceived(order, context.getPaymentResult());

        // Confirm: save to DB (day la lan dau order hit DB trong async flow)
        onConfirming(order);
        OrderAccessor.transitionTo(order, OrderStatus.CONFIRMED);
        order = saveOrder(order);
        context.setOrder(order);
        onConfirmed(order);

        // Publish event
        getConfirmationStep().execute(context);

        // Cleanup saga state
        sagaStateRepository.delete(order.getOrderId());

        log.info("[Saga-Async] Confirmed: orderId={}", order.getOrderId());
        return order;
    }

    private O handlePaymentFailure(SagaContext<O> context, FailureReason reason, String message) {
        O order = context.getOrder();
        onPaymentResultReceived(order, context.getPaymentResult());

        // Compensate reservation (release inventory)
        compensate(context);

        // Cancel order
        return cancelOrder(context, reason, message);
    }

    private FailureReason mapPaymentFailure(PaymentResult result) {
        if (result.isFailed()) return FailureReason.PAYMENT_FAILED;
        if (result.isTimeout()) return FailureReason.PAYMENT_TIMEOUT;
        return FailureReason.PAYMENT_UNKNOWN;
    }
}
