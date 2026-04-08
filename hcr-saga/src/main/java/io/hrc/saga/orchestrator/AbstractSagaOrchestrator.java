package io.hrc.saga.orchestrator;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.domain.OrderAccessor;
import io.hrc.core.domain.OrderRequest;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.enums.OrderStatus;
import io.hrc.core.exception.FrameworkException;
import io.hrc.core.exception.ValidationException;
import io.hrc.core.result.ValidationResult;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.event.order.OrderCancelledEvent;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.saga.context.SagaContext;
import io.hrc.saga.repository.SagaStateRepository;
import io.hrc.saga.step.ConfirmationStep;
import io.hrc.saga.step.PaymentStep;
import io.hrc.saga.step.ReservationStep;
import io.hrc.saga.step.SagaStep;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Template Method base cho toan bo Saga flow.
 *
 * <p><b>Framework-controlled (DO NOT override):</b> {@link #process},
 * {@link #retryPayment}, {@link #adminCancel}, {@link #getStatus}.
 *
 * <p><b>Developer BAT BUOC implement:</b>
 * <ul>
 *   <li>{@link #createOrder} — tao order entity tu request</li>
 *   <li>{@link #findOrder} — load order tu DB theo orderId</li>
 *   <li>{@link #saveOrder} — persist order xuong DB</li>
 *   <li>{@link #buildPaymentRequest} — build PaymentRequest tu order</li>
 *   <li>{@link #onConfirmed} — callback sau khi confirm thanh cong</li>
 *   <li>{@link #onCancelled} — callback sau khi cancel</li>
 * </ul>
 *
 * <p><b>Optional lifecycle hooks:</b> {@link #onReserving}, {@link #onPaymentProcessing},
 * {@link #onConfirming}, {@link #onCancelling}, {@link #onCompensating}.
 *
 * <p><b>Config override:</b> {@link #getReservationTimeoutMinutes} (default 5),
 * {@link #allowPartialFulfillment} (default false).
 *
 * @param <REQ> kieu request cu the cua developer (extends OrderRequest)
 * @param <O>   kieu order cu the cua developer (extends AbstractOrder)
 */
@Slf4j
public abstract class AbstractSagaOrchestrator<
        REQ extends OrderRequest,
        O extends AbstractOrder> {

    protected final InventoryStrategy inventoryStrategy;
    protected final PaymentGateway paymentGateway;
    protected final EventBus eventBus;
    protected final SagaStateRepository<O> sagaStateRepository;

    // Steps — tao lazy khi can
    private ReservationStep<O> reservationStep;
    private PaymentStep<O> paymentStep;
    private ConfirmationStep<O> confirmationStep;

    protected AbstractSagaOrchestrator(InventoryStrategy inventoryStrategy,
                                        PaymentGateway paymentGateway,
                                        EventBus eventBus,
                                        SagaStateRepository<O> sagaStateRepository) {
        this.inventoryStrategy = inventoryStrategy;
        this.paymentGateway = paymentGateway;
        this.eventBus = eventBus;
        this.sagaStateRepository = sagaStateRepository;
    }

    // =========================================================================
    // MAIN FLOW — final, developer KHONG override
    // =========================================================================

    /**
     * Xu ly 1 order request tu dau den cuoi.
     *
     * <p><b>Sync (P1/P2):</b> Validate → Create order → Reserve(DB) → Charge → Confirm → return.
     * <br><b>Async (P3):</b> Validate → Reserve(Redis) → Publish event → return.
     *
     * @param request request tu client
     * @return order voi status cuoi cung (CONFIRMED cho sync, RESERVED cho async)
     * @throws ValidationException  neu request khong hop le
     * @throws FrameworkException   neu loi he thong
     */
    public final O process(REQ request) {
        // 1. Validate
        validateRequest(request);

        // 2. Create order
        O order = createOrder(request);
        order.setExpiresAt(Instant.now().plusSeconds(getReservationTimeoutMinutes() * 60L));

        // 3. Build context
        SagaContext<O> context = new SagaContext<>(order, generateCorrelationId());

        log.info("[Saga] Processing: orderId={}, resourceId={}, qty={}",
                order.getOrderId(), order.getResourceId(), order.getQuantity());

        // 4. Delegate cho subclass (sync hoac async)
        return executeFlow(context);
    }

    /**
     * Retry payment cho order da RESERVED nhung payment fail truoc do.
     *
     * @param orderId ID order can retry
     * @return order voi status moi
     * @throws IllegalArgumentException neu order khong ton tai
     * @throws IllegalStateException    neu order khong o trang thai RESERVED
     */
    public final O retryPayment(String orderId) {
        O order = findOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order không tồn tại: " + orderId);
        }
        if (order.getStatus() != OrderStatus.RESERVED) {
            throw new IllegalStateException(
                    "Chỉ retry payment cho order RESERVED, hiện tại: " + order.getStatus());
        }
        if (order.isExpired()) {
            return expireOrder(order);
        }

        SagaContext<O> context = new SagaContext<>(order, generateCorrelationId());
        context.markStepCompleted("reservation");

        return executePaymentAndConfirmation(context);
    }

    /**
     * Admin cancel order thu cong.
     *
     * @param orderId order can cancel
     * @param reason  ly do cancel
     * @return order da cancel
     * @throws IllegalArgumentException neu order khong ton tai
     * @throws IllegalStateException    neu order da o terminal state
     */
    public final O adminCancel(String orderId, String reason) {
        O order = findOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order không tồn tại: " + orderId);
        }
        if (order.isTerminal()) {
            throw new IllegalStateException(
                    "Order đã ở terminal state: " + order.getStatus());
        }

        SagaContext<O> context = new SagaContext<>(order, generateCorrelationId());

        // Compensate neu da reserve
        if (order.getStatus() == OrderStatus.RESERVED) {
            context.markStepCompleted("reservation");
            compensate(context);
        }

        return cancelOrder(context, null, reason);
    }

    /**
     * Lay status hien tai cua order.
     * Kiem tra SagaStateRepository truoc (async mode), roi fallback sang findOrder.
     */
    public final OrderStatus getStatus(String orderId) {
        if (sagaStateRepository != null) {
            var sagaContext = sagaStateRepository.findByOrderId(orderId);
            if (sagaContext.isPresent()) {
                return sagaContext.get().getOrder().getStatus();
            }
        }
        O order = findOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("Order không tồn tại: " + orderId);
        }
        return order.getStatus();
    }

    // =========================================================================
    // TEMPLATE METHODS — subclass implement
    // =========================================================================

    /**
     * Diem phan nhanh giua sync va async.
     * Sync: reserve → charge → confirm → return order.
     * Async: reserve → publish event → return order (RESERVED).
     */
    protected abstract O executeFlow(SagaContext<O> context);

    // =========================================================================
    // DEVELOPER BAT BUOC IMPLEMENT
    // =========================================================================

    /**
     * Tao order entity tu request. Framework goi truoc khi bat dau Saga.
     * Developer phai set orderId (thuong la UUID).
     */
    protected abstract O createOrder(REQ request);

    /** Load order tu DB theo orderId. Tra null neu khong tim thay. */
    protected abstract O findOrder(String orderId);

    /** Persist order xuong DB. Tra ve order da save (co the co generated fields). */
    protected abstract O saveOrder(O order);

    /** Build PaymentRequest tu order — Saga truyen vao PaymentStep. */
    protected abstract PaymentRequest buildPaymentRequest(O order);

    /** Callback sau khi order CONFIRMED thanh cong. */
    protected abstract void onConfirmed(O order);

    /** Callback sau khi order bi CANCELLED. */
    protected abstract void onCancelled(O order, String reason);

    // =========================================================================
    // LIFECYCLE HOOKS — developer override neu can
    // =========================================================================

    protected void onReserving(O order) {
    }

    protected void onPaymentProcessing(O order) {
    }

    protected void onConfirming(O order) {
    }

    protected void onCancelling(O order) {
    }

    protected void onCompensating(O order) {
    }

    // =========================================================================
    // CONFIGURATION — developer override neu can
    // =========================================================================

    /** Thoi gian giu cho toi da (phut). Default: 5. */
    protected int getReservationTimeoutMinutes() {
        return 5;
    }

    /** Cho phep dat 1 phan neu khong du hang? Default: false. */
    protected boolean allowPartialFulfillment() {
        return false;
    }

    // =========================================================================
    // INTERNAL — dung chung cho Sync va Async
    // =========================================================================

    /**
     * Thuc thi payment + confirmation (dung cho sync flow va retryPayment).
     */
    protected O executePaymentAndConfirmation(SagaContext<O> context) {
        O order = context.getOrder();

        // Payment
        onPaymentProcessing(order);
        var paymentResult = getPaymentStep().execute(context);
        if (!paymentResult.isSuccess()) {
            context.markStepFailed("payment");
            compensate(context);
            return cancelOrder(context, paymentResult.getFailureReason(),
                    paymentResult.getErrorMessage());
        }
        context.markStepCompleted("payment");

        // Confirm
        onConfirming(order);
        OrderAccessor.transitionTo(order, OrderStatus.CONFIRMED);
        order = saveOrder(order);
        context.setOrder(order);
        onConfirmed(order);

        // Publish event
        getConfirmationStep().execute(context);
        context.markStepCompleted("confirmation");

        // Cleanup saga state
        if (sagaStateRepository != null) {
            sagaStateRepository.delete(order.getOrderId());
        }

        log.info("[Saga] Confirmed: orderId={}", order.getOrderId());
        return order;
    }

    /**
     * Compensate cac buoc da hoan thanh — theo thu tu NGUOC.
     */
    protected void compensate(SagaContext<O> context) {
        O order = context.getOrder();
        onCompensating(order);

        List<String> completed = context.getCompletedSteps();
        log.info("[Saga] Compensating {} steps: orderId={}, steps={}",
                completed.size(), order.getOrderId(), completed);

        // Compensate theo thu tu nguoc
        for (int i = completed.size() - 1; i >= 0; i--) {
            String stepName = completed.get(i);
            try {
                switch (stepName) {
                    case "payment" -> getPaymentStep().compensate(context);
                    case "reservation" -> getReservationStep().compensate(context);
                    case "confirmation" -> getConfirmationStep().compensate(context);
                    default -> log.warn("[Saga] Unknown step to compensate: {}", stepName);
                }
            } catch (Exception e) {
                log.error("[Saga] Compensate failed for step {}, continuing: orderId={}",
                        stepName, order.getOrderId(), e);
            }
        }
    }

    /**
     * Cancel order: transition → CANCELLED, save, publish event, callback.
     */
    protected O cancelOrder(SagaContext<O> context, FailureReason reason, String message) {
        O order = context.getOrder();
        onCancelling(order);

        // Transition qua COMPENSATING neu dang RESERVED
        if (order.getStatus() == OrderStatus.RESERVED) {
            OrderAccessor.transitionTo(order, OrderStatus.COMPENSATING);
        }
        OrderAccessor.transitionTo(order, OrderStatus.CANCELLED);
        if (reason != null) {
            OrderAccessor.markFailedWith(order, reason);
        }
        order = saveOrder(order);
        context.setOrder(order);

        // Publish cancel event
        eventBus.publish(new OrderCancelledEvent(
                order.getResourceId(), order.getOrderId(),
                order.getRequesterId(), order.getQuantity(),
                reason, context.getCorrelationId()));

        onCancelled(order, message);

        // Cleanup saga state
        if (sagaStateRepository != null) {
            sagaStateRepository.delete(order.getOrderId());
        }

        log.info("[Saga] Cancelled: orderId={}, reason={}", order.getOrderId(), reason);
        return order;
    }

    /**
     * Expire order: compensate reservation + cancel voi RESERVATION_EXPIRED.
     */
    protected O expireOrder(O order) {
        SagaContext<O> context = new SagaContext<>(order, generateCorrelationId());
        if (order.getStatus() == OrderStatus.RESERVED) {
            context.markStepCompleted("reservation");
            compensate(context);
        }
        OrderAccessor.transitionTo(order, OrderStatus.EXPIRED);
        OrderAccessor.markFailedWith(order, FailureReason.RESERVATION_EXPIRED);
        order = saveOrder(order);
        context.setOrder(order);

        if (sagaStateRepository != null) {
            sagaStateRepository.delete(order.getOrderId());
        }

        log.info("[Saga] Expired: orderId={}", order.getOrderId());
        return order;
    }

    /**
     * Validate request co ban: resourceId, quantity, idempotencyKey.
     * Sau do goi developer's validateRequest().
     */
    protected void validateRequest(REQ request) {
        ValidationResult result = ValidationResult.ok();

        if (request.getResourceId() == null || request.getResourceId().isBlank()) {
            result = result.merge(ValidationResult.fail("resourceId", "không được để trống"));
        }
        if (request.getQuantity() <= 0) {
            result = result.merge(ValidationResult.fail("quantity", "phải > 0"));
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            result = result.merge(ValidationResult.fail("idempotencyKey", "không được để trống"));
        }

        result.throwIfInvalid();

        // Developer custom validation
        request.validateRequest();
    }

    protected String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    // =========================================================================
    // Step lazy initialization
    // =========================================================================

    protected ReservationStep<O> getReservationStep() {
        if (reservationStep == null) {
            reservationStep = new ReservationStep<>(inventoryStrategy);
        }
        return reservationStep;
    }

    protected PaymentStep<O> getPaymentStep() {
        if (paymentStep == null) {
            paymentStep = new PaymentStep<>(paymentGateway, this::buildPaymentRequest);
        }
        return paymentStep;
    }

    protected ConfirmationStep<O> getConfirmationStep() {
        if (confirmationStep == null) {
            confirmationStep = new ConfirmationStep<>(eventBus);
        }
        return confirmationStep;
    }
}
