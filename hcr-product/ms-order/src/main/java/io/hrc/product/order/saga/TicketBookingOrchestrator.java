package io.hrc.product.order.saga;

import io.hrc.core.domain.DomainEvent;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.product.order.domain.ConcertTicket;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.product.order.domain.TicketRequest;
import io.hrc.product.order.repository.ConcertTicketRepository;
import io.hrc.product.order.repository.TicketOrderRepository;
import io.hrc.product.shared.event.PaymentRequestedEvent;
import io.hrc.saga.context.SagaContext;
import io.hrc.saga.orchestrator.async.AsynchronousSagaOrchestrator;
import io.hrc.saga.repository.SagaStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Async saga cho đặt vé concert. Reserve Redis sync, payment xử lý qua Kafka events.
 *
 * <p>Override {@link #buildOrderCreatedEvent} để publish {@link PaymentRequestedEvent}
 * thay vì framework's OrderCreatedEvent — vì ms-payment cần biết amount + currency.
 */
@Service
@Slf4j
public class TicketBookingOrchestrator
        extends AsynchronousSagaOrchestrator<TicketRequest, TicketOrder> {

    private final TicketOrderRepository orderRepository;
    private final ConcertTicketRepository catalogRepository;

    public TicketBookingOrchestrator(InventoryStrategy inventoryStrategy,
                                      PaymentGateway paymentGateway,
                                      EventBus eventBus,
                                      SagaStateRepository<TicketOrder> sagaStateRepository,
                                      TicketOrderRepository orderRepository,
                                      ConcertTicketRepository catalogRepository) {
        super(inventoryStrategy, paymentGateway, eventBus, sagaStateRepository);
        this.orderRepository = orderRepository;
        this.catalogRepository = catalogRepository;
    }

    @Override
    protected TicketOrder createOrder(TicketRequest request) {
        ConcertTicket catalog = catalogRepository.findById(request.getResourceId()).orElse(null);

        TicketOrder order = new TicketOrder();
        order.setOrderId(UUID.randomUUID().toString());
        order.setResourceId(request.getResourceId());
        order.setRequesterId(request.getRequesterId());
        order.setQuantity(request.getQuantity());
        order.setIdempotencyKey(request.getIdempotencyKey());

        if (catalog != null) {
            order.setConcertName(catalog.getConcertName());
            order.setCurrency(catalog.getCurrency());
            order.setTotalAmount(catalog.getPricePerTicket()
                    .multiply(BigDecimal.valueOf(request.getQuantity())));
        } else {
            // Resource không có catalog — set defaults; reserve sẽ tự fail nếu Redis không init
            order.setCurrency("VND");
            order.setTotalAmount(BigDecimal.ZERO);
            log.warn("[ms-order] No catalog for resourceId={}, totalAmount defaults to 0",
                    request.getResourceId());
        }
        return order;
    }

    @Override
    protected TicketOrder findOrder(String orderId) {
        return orderRepository.findByOrderId(orderId).orElse(null);
    }

    @Override
    protected TicketOrder saveOrder(TicketOrder order) {
        return orderRepository.save(order);
    }

    @Override
    protected PaymentRequest buildPaymentRequest(TicketOrder order) {
        // Sync path không dùng — async qua PaymentRequestedEvent. Giữ stub cho retry/admin path.
        return PaymentRequest.builder()
                .transactionId(order.getOrderId())
                .amount(order.getTotalAmount().longValueExact())
                .currency(order.getCurrency())
                .description("Concert ticket: " + order.getConcertName())
                .build();
    }

    @Override
    protected DomainEvent buildOrderCreatedEvent(SagaContext<TicketOrder> context) {
        TicketOrder order = context.getOrder();
        return new PaymentRequestedEvent(
                order.getResourceId(),
                order.getOrderId(),
                order.getRequesterId(),
                order.getTotalAmount(),
                order.getCurrency(),
                context.getCorrelationId());
    }

    @Override
    protected void onConfirmed(TicketOrder order) {
        log.info("[ms-order] Confirmed: orderId={}, total={} {}",
                order.getOrderId(), order.getTotalAmount(), order.getCurrency());
    }

    @Override
    protected void onCancelled(TicketOrder order, String reason) {
        log.warn("[ms-order] Cancelled: orderId={}, reason={}", order.getOrderId(), reason);
    }
}
