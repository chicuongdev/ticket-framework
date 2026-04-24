package io.hrc.product.payment.listener;

import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.EventHandler;
import io.hrc.eventbus.event.payment.PaymentFailedEvent;
import io.hrc.eventbus.event.payment.PaymentSucceededEvent;
import io.hrc.eventbus.event.payment.PaymentTimeoutEvent;
import io.hrc.eventbus.event.payment.PaymentUnknownEvent;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.payment.model.PaymentResult;
import io.hrc.product.payment.domain.PaymentAttempt;
import io.hrc.product.payment.domain.PaymentAttemptStatus;
import io.hrc.product.payment.repository.PaymentAttemptRepository;
import io.hrc.product.shared.event.PaymentRequestedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Tự subscribe {@link PaymentRequestedEvent} từ Kafka khi Spring khởi tạo bean.
 *
 * <p>Idempotency: PaymentAttempt PK = orderId. Nếu attempt đã ở trạng thái
 * resolved (SUCCEEDED/FAILED/TIMEOUT/UNKNOWN), event lặp lại sẽ bị bỏ qua —
 * không re-charge gateway.
 */
@Component
@Slf4j
public class PaymentRequestedListener implements InitializingBean, DisposableBean {

    private final EventBus eventBus;
    private final PaymentGateway paymentGateway;
    private final PaymentAttemptRepository repository;

    private EventHandler<PaymentRequestedEvent> handler;

    public PaymentRequestedListener(EventBus eventBus,
                                     PaymentGateway paymentGateway,
                                     PaymentAttemptRepository repository) {
        this.eventBus = eventBus;
        this.paymentGateway = paymentGateway;
        this.repository = repository;
    }

    @Override
    public void afterPropertiesSet() {
        this.handler = (event, ack) -> {
            try {
                process(event);
            } catch (Exception e) {
                log.error("[ms-payment] Unexpected error processing event {}: {}",
                        event.getEventId(), e.getMessage(), e);
            } finally {
                ack.acknowledge();
            }
        };
        eventBus.subscribe(PaymentRequestedEvent.class, handler);
        log.info("[ms-payment] Subscribed to PaymentRequestedEvent");
    }

    @Override
    public void destroy() {
        if (handler != null) {
            eventBus.unsubscribe(PaymentRequestedEvent.class, handler);
        }
    }

    /**
     * Không dùng {@code @Transactional} vì self-invocation từ lambda bypass Spring proxy.
     * Mỗi {@code repository.save()} chạy 1 TX riêng — cũng tốt hơn vì tránh giữ DB
     * connection xuyên suốt {@code gateway.charge()} (có thể mất tới 5s).
     */
    protected void process(PaymentRequestedEvent event) {
        String orderId = event.getOrderId();

        Optional<PaymentAttempt> existing = repository.findById(orderId);
        if (existing.isPresent() && existing.get().getStatus() != PaymentAttemptStatus.PENDING) {
            log.info("[ms-payment] Skip duplicate event: orderId={}, status={}",
                    orderId, existing.get().getStatus());
            return;
        }

        PaymentAttempt attempt = existing.orElseGet(() -> repository.save(
                new PaymentAttempt(orderId, event.getResourceId(),
                        event.getAmount(), event.getCurrency())));

        long startMs = System.currentTimeMillis();

        PaymentRequest request = PaymentRequest.builder()
                .transactionId(orderId)
                .amount(toMinorUnits(event.getAmount(), event.getCurrency()))
                .currency(event.getCurrency())
                .description("Payment for order " + orderId)
                .build();

        PaymentResult result = paymentGateway.charge(request);
        Duration elapsed = Duration.ofMillis(System.currentTimeMillis() - startMs);

        publishOutcome(event, result, elapsed);
        persistOutcome(attempt, result);
    }

    private void publishOutcome(PaymentRequestedEvent event, PaymentResult result, Duration elapsed) {
        String resourceId = event.getResourceId();
        String orderId = event.getOrderId();
        String correlationId = event.getCorrelationId();

        if (result.isSuccess()) {
            eventBus.publish(new PaymentSucceededEvent(
                    resourceId, orderId,
                    result.getGatewayTransactionId(),
                    event.getAmount(), event.getCurrency(),
                    correlationId));
        } else if (result.isTimeout()) {
            eventBus.publish(new PaymentTimeoutEvent(
                    resourceId, orderId, elapsed, correlationId));
        } else if (result.isUnknown()) {
            eventBus.publish(new PaymentUnknownEvent(
                    resourceId, orderId, result.getErrorMessage(), correlationId));
        } else {
            eventBus.publish(new PaymentFailedEvent(
                    resourceId, orderId, result.getErrorMessage(), correlationId));
        }
    }

    private void persistOutcome(PaymentAttempt attempt, PaymentResult result) {
        PaymentAttemptStatus status;
        if (result.isSuccess()) status = PaymentAttemptStatus.SUCCEEDED;
        else if (result.isTimeout()) status = PaymentAttemptStatus.TIMEOUT;
        else if (result.isUnknown()) status = PaymentAttemptStatus.UNKNOWN;
        else status = PaymentAttemptStatus.FAILED;

        attempt.markResolved(status,
                result.getGatewayTransactionId(),
                result.getErrorCode(),
                result.getErrorMessage());
        repository.save(attempt);
    }

    /** Framework PaymentRequest.amount kiểu long (đơn vị nhỏ nhất). VND không có cent → giữ nguyên. */
    private long toMinorUnits(BigDecimal amount, String currency) {
        if ("VND".equalsIgnoreCase(currency) || "JPY".equalsIgnoreCase(currency)) {
            return amount.longValueExact();
        }
        return amount.movePointRight(2).longValueExact();
    }
}
