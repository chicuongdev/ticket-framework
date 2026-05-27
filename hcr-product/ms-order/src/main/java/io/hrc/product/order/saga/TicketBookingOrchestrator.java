package io.hrc.product.order.saga;

import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.product.order.domain.ConcertTicket;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.product.order.domain.TicketRequest;
import io.hrc.product.order.repository.TicketOrderRepository;
import io.hrc.product.order.service.ConcertTicketCatalog;
import io.hrc.saga.orchestrator.async.AsynchronousSagaOrchestrator;
import io.hrc.saga.payment.PaymentInitiationStrategy;
import io.hrc.saga.repository.SagaStateRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Async saga cho dat ve concert. Reserve Redis sync, payment xu ly nen async qua
 * {@link PaymentInitiationStrategy} (default {@code AutoChargeInitiation}: HTTP sang
 * ms-payment + goi thang handlePaymentResult khi xong).
 */
@Service
@ConditionalOnProperty(name = "hcr.saga.mode", havingValue = "async", matchIfMissing = true)
@Slf4j
public class TicketBookingOrchestrator
        extends AsynchronousSagaOrchestrator<TicketRequest, TicketOrder> {

    private final TicketOrderRepository orderRepository;
    private final ConcertTicketCatalog catalog;
    private final Timer catalogLookupTimer;

    public TicketBookingOrchestrator(InventoryStrategy inventoryStrategy,
                                      PaymentGateway paymentGateway,
                                      EventBus eventBus,
                                      SagaStateRepository<TicketOrder> sagaStateRepository,
                                      PaymentInitiationStrategy<TicketOrder> paymentInitiationStrategy,
                                      TicketOrderRepository orderRepository,
                                      ConcertTicketCatalog catalog,
                                      MeterRegistry meterRegistry) {
        super(inventoryStrategy, paymentGateway, eventBus, sagaStateRepository,
                paymentInitiationStrategy);
        this.orderRepository = orderRepository;
        this.catalog = catalog;
        this.catalogLookupTimer = Timer.builder("ms_order_catalog_lookup_duration_ms")
                .description("ConcertTicket catalog lookup per POST /orders (in-memory cache after Step 2)")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    @Override
    protected TicketOrder createOrder(TicketRequest request) {
        long catalogStart = System.nanoTime();
        ConcertTicket ticket = catalog.findById(request.getResourceId()).orElse(null);
        catalogLookupTimer.record(System.nanoTime() - catalogStart, TimeUnit.NANOSECONDS);

        TicketOrder order = new TicketOrder();
        order.setOrderId(UUID.randomUUID().toString());
        order.setResourceId(request.getResourceId());
        order.setRequesterId(request.getRequesterId());
        order.setQuantity(request.getQuantity());
        order.setIdempotencyKey(request.getIdempotencyKey());

        if (ticket != null) {
            order.setConcertName(ticket.getConcertName());
            order.setCurrency(ticket.getCurrency());
            order.setTotalAmount(ticket.getPricePerTicket()
                    .multiply(BigDecimal.valueOf(request.getQuantity())));
        } else {
            // Resource khong co catalog — set defaults; reserve se tu fail neu Redis khong init
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
        // Saga goi:
        //  - P1/P2 sync: PaymentStep.charge() -> RemotePaymentGateway -> POST ms-payment
        //  - P3 async: AutoChargeInitiation.charge() -> RemotePaymentGateway -> POST ms-payment
        // resourceId dat trong metadata de ms-payment ghi vao PaymentAttempt.
        return PaymentRequest.builder()
                .transactionId(order.getOrderId())
                .amount(order.getTotalAmount().longValueExact())
                .currency(order.getCurrency())
                .description("Concert ticket: " + order.getConcertName())
                .metadata(java.util.Map.of("resourceId", order.getResourceId()))
                .build();
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
