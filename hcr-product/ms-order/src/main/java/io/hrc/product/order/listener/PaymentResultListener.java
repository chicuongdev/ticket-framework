package io.hrc.product.order.listener;

import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.EventHandler;
import io.hrc.eventbus.event.payment.PaymentFailedEvent;
import io.hrc.eventbus.event.payment.PaymentSucceededEvent;
import io.hrc.eventbus.event.payment.PaymentTimeoutEvent;
import io.hrc.eventbus.event.payment.PaymentUnknownEvent;
import io.hrc.payment.model.PaymentResult;
import io.hrc.product.order.saga.TicketBookingOrchestrator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Subscribe 4 payment outcome events từ ms-payment, đẩy vào saga orchestrator
 * để confirm/cancel order.
 */
@Component
@Slf4j
public class PaymentResultListener implements InitializingBean, DisposableBean {

    private final EventBus eventBus;
    private final TicketBookingOrchestrator orchestrator;

    private EventHandler<PaymentSucceededEvent> succeededHandler;
    private EventHandler<PaymentFailedEvent> failedHandler;
    private EventHandler<PaymentTimeoutEvent> timeoutHandler;
    private EventHandler<PaymentUnknownEvent> unknownHandler;

    public PaymentResultListener(EventBus eventBus,
                                  TicketBookingOrchestrator orchestrator) {
        this.eventBus = eventBus;
        this.orchestrator = orchestrator;
    }

    @Override
    public void afterPropertiesSet() {
        succeededHandler = (event, ack) -> handle(event.getOrderId(),
                PaymentResult.success(event.getOrderId(), event.getTransactionId(),
                        event.getAmount().longValueExact()), ack);
        failedHandler = (event, ack) -> handle(event.getOrderId(),
                PaymentResult.failed(event.getOrderId(), "PAYMENT_FAILED",
                        event.getGatewayMessage()), ack);
        timeoutHandler = (event, ack) -> handle(event.getOrderId(),
                PaymentResult.timeout(event.getOrderId()), ack);
        unknownHandler = (event, ack) -> handle(event.getOrderId(),
                PaymentResult.unknown(event.getOrderId()), ack);

        eventBus.subscribe(PaymentSucceededEvent.class, succeededHandler);
        eventBus.subscribe(PaymentFailedEvent.class, failedHandler);
        eventBus.subscribe(PaymentTimeoutEvent.class, timeoutHandler);
        eventBus.subscribe(PaymentUnknownEvent.class, unknownHandler);
        log.info("[ms-order] Subscribed Payment{Succeeded,Failed,Timeout,Unknown}Event");
    }

    @Override
    public void destroy() {
        if (succeededHandler != null) eventBus.unsubscribe(PaymentSucceededEvent.class, succeededHandler);
        if (failedHandler != null) eventBus.unsubscribe(PaymentFailedEvent.class, failedHandler);
        if (timeoutHandler != null) eventBus.unsubscribe(PaymentTimeoutEvent.class, timeoutHandler);
        if (unknownHandler != null) eventBus.unsubscribe(PaymentUnknownEvent.class, unknownHandler);
    }

    private void handle(String orderId, PaymentResult result, io.hrc.eventbus.Acknowledgment ack) {
        try {
            orchestrator.handlePaymentResult(orderId, result);
        } catch (IllegalStateException e) {
            // Saga state expired or already processed — ack and skip
            log.warn("[ms-order] Drop payment result for {}: {}", orderId, e.getMessage());
        } catch (Exception e) {
            log.error("[ms-order] Failed to apply payment result for {}: {}",
                    orderId, e.getMessage(), e);
        } finally {
            ack.acknowledge();
        }
    }
}
