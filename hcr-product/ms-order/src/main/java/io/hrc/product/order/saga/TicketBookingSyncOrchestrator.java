package io.hrc.product.order.saga;

import io.hrc.core.domain.OrderAccessor;
import io.hrc.core.enums.OrderStatus;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.product.order.repository.TicketOrderRepository;
import io.hrc.product.order.service.ConcertTicketCatalog;
import io.hrc.saga.context.SagaContext;
import io.hrc.saga.payment.PaymentInitiationStrategy;
import io.hrc.saga.repository.SagaStateRepository;
import io.hrc.saga.step.StepResult;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Saga dong bo cho P1 (pessimistic-lock) va P2 (optimistic-lock).
 *
 * <p>Ke thua nguyen 6 method nghiep vu tu {@link TicketBookingOrchestrator}
 * (createOrder / findOrder / saveOrder / buildPaymentRequest / onConfirmed / onCancelled)
 * — chi override {@link #executeFlow} de doi luong async sang luong dong bo:
 * save PENDING -> reserve(DB) -> RESERVED -> charge + confirm. Order tra ve da CONFIRMED
 * ngay trong request (controller map -> HTTP 201 Created).
 *
 * <p>Chi tao bean khi {@code hcr.saga.mode=sync}. O mode async (P3), bean nay khong ton tai,
 * {@link TicketBookingOrchestrator} (async) duoc dung. Controller inject theo kieu lop cha
 * nen khong can biet dang chay mode nao.
 *
 * <p>Logic luong dong bo sao chep tu {@code SynchronousSagaOrchestrator.executeFlow} — khong
 * the extends truc tiep class do vi Java don ke thua (da extends async qua lop cha).
 */
@Service
@ConditionalOnProperty(name = "hcr.saga.mode", havingValue = "sync")
public class TicketBookingSyncOrchestrator extends TicketBookingOrchestrator {

    public TicketBookingSyncOrchestrator(InventoryStrategy inventoryStrategy,
                                          PaymentGateway paymentGateway,
                                          EventBus eventBus,
                                          SagaStateRepository<TicketOrder> sagaStateRepository,
                                          PaymentInitiationStrategy<TicketOrder> paymentInitiationStrategy,
                                          TicketOrderRepository orderRepository,
                                          ConcertTicketCatalog catalog,
                                          MeterRegistry meterRegistry) {
        super(inventoryStrategy, paymentGateway, eventBus, sagaStateRepository,
                paymentInitiationStrategy, orderRepository, catalog, meterRegistry);
    }

    /**
     * Luong dong bo (P1/P2): save PENDING -> reserve(DB) -> RESERVED -> payment + confirm.
     * Reserve fail -> cancel ngay; payment fail -> compensate (release inventory) -> cancel.
     */
    @Override
    protected TicketOrder executeFlow(SagaContext<TicketOrder> context) {
        TicketOrder order = context.getOrder();

        // Save order PENDING xuong DB
        order = saveOrder(order);
        context.setOrder(order);

        // Step 1: Reserve inventory (DB — SELECT FOR UPDATE / @Version, blocking)
        onReserving(order);
        StepResult reserveResult = getReservationStep().execute(context);
        if (!reserveResult.isSuccess()) {
            context.markStepFailed("reservation");
            return cancelOrder(context, reserveResult.getFailureReason(),
                    reserveResult.getErrorMessage());
        }
        context.markStepCompleted("reservation");

        // Transition PENDING -> RESERVED
        OrderAccessor.transitionTo(order, OrderStatus.RESERVED);
        order = saveOrder(order);
        context.setOrder(order);

        // Step 2 + 3: Payment + Confirmation (blocking, compensate inline neu fail)
        return executePaymentAndConfirmation(context);
    }
}
