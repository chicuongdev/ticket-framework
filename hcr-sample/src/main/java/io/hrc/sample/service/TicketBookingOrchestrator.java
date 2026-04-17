package io.hrc.sample.service;

import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.sample.domain.ConcertTicket;
import io.hrc.sample.domain.TicketOrder;
import io.hrc.sample.domain.TicketRequest;
import io.hrc.sample.repository.TicketOrderRepository;
import io.hrc.saga.orchestrator.sync.SynchronousSagaOrchestrator;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Saga orchestrator cho đặt vé concert — extends {@link SynchronousSagaOrchestrator}.
 *
 * <p>Developer chỉ implement 6 abstract method nghiệp vụ.
 * Framework lo toàn bộ flow: validate → reserve → charge → confirm → rollback nếu fail.
 */
@Service
@Slf4j
public class TicketBookingOrchestrator
        extends SynchronousSagaOrchestrator<TicketRequest, TicketOrder> {

    private final TicketOrderRepository orderRepository;
    private final EntityManager entityManager;

    public TicketBookingOrchestrator(InventoryStrategy inventoryStrategy,
                                      PaymentGateway paymentGateway,
                                      EventBus eventBus,
                                      TicketOrderRepository orderRepository,
                                      EntityManager entityManager) {
        super(inventoryStrategy, paymentGateway, eventBus);
        this.orderRepository = orderRepository;
        this.entityManager = entityManager;
    }

    // =========================================================================
    // Developer PHẢI implement
    // =========================================================================

    @Override
    protected TicketOrder createOrder(TicketRequest request) {
        ConcertTicket ticket = entityManager.find(ConcertTicket.class, request.getResourceId());

        TicketOrder order = new TicketOrder();
        order.setOrderId(UUID.randomUUID().toString());
        order.setResourceId(request.getResourceId());
        order.setRequesterId(request.getRequesterId());
        order.setQuantity(request.getQuantity());
        order.setIdempotencyKey(request.getIdempotencyKey());
        order.setBuyerEmail(request.getBuyerEmail());

        if (ticket != null) {
            order.setConcertName(ticket.getConcertName());
            order.setTotalAmount(order.calculateTotal(ticket.getPricePerTicket()));
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
        return PaymentRequest.builder()
                .transactionId(order.getOrderId())
                .amount(order.getTotalAmount())
                .currency("VND")
                .description("Vé concert: " + order.getConcertName())
                .build();
    }

    @Override
    protected void onConfirmed(TicketOrder order) {
        log.info("[Sample] Đơn hàng đã xác nhận: orderId={}, buyer={}, resource={}",
                order.getOrderId(), order.getBuyerEmail(), order.getResourceId());
        // Developer có thể gửi email xác nhận, push notification...
    }

    @Override
    protected void onCancelled(TicketOrder order, String reason) {
        log.warn("[Sample] Đơn hàng bị hủy: orderId={}, reason={}", order.getOrderId(), reason);
        // Developer có thể gửi email thông báo hủy...
    }
}
