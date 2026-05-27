package io.hrc.saga.payment;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.payment.model.PaymentResult;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * Card-on-file auto-charge: ngay sau reserve, submit {@link PaymentGateway#charge}
 * len 1 background {@link Executor} de giai phong HTTP request (giu HTTP 202 nhanh).
 * Khi charge xong, goi THANG outcomeHandler — thuong la
 * {@code AsynchronousSagaOrchestrator::handlePaymentResult} — de confirm/cancel order.
 *
 * <p><b>Khac biet voi luong {@code PaymentRequestedEvent} cu:</b> khong qua Kafka, khong
 * roundtrip publish-rồi-tự-consume. Saga handler nhan PaymentResult truc tiep tu
 * background thread, tiet kiem 1 hop khi deploy voi Kafka EventBus.
 *
 * <p><b>Failure mode:</b> neu {@code charge()} throw exception → tao
 * {@link PaymentResult#unknown(String)} va goi outcomeHandler; reconciliation cycle
 * sau se hoi lai gateway de chot.
 *
 * @param <O> kieu order cu the cua developer
 */
@Slf4j
public class AutoChargeInitiation<O extends AbstractOrder> implements PaymentInitiationStrategy<O> {

    private final PaymentGateway paymentGateway;
    private final Executor executor;
    private final BiConsumer<String, PaymentResult> outcomeHandler;

    /**
     * @param paymentGateway gateway charge sang ben thu 3 (vd: RemotePaymentGateway HTTP)
     * @param executor       background pool — KHONG dung HTTP worker pool de tranh
     *                       starvation khi gateway cham
     * @param outcomeHandler callback nhan (orderId, result) — thuong la
     *                       {@code orchestrator::handlePaymentResult}. Wire qua
     *                       {@code @Lazy} de tranh circular dependency voi orchestrator.
     */
    public AutoChargeInitiation(PaymentGateway paymentGateway,
                                 Executor executor,
                                 BiConsumer<String, PaymentResult> outcomeHandler) {
        if (paymentGateway == null) throw new IllegalArgumentException("paymentGateway null");
        if (executor == null) throw new IllegalArgumentException("executor null");
        if (outcomeHandler == null) throw new IllegalArgumentException("outcomeHandler null");
        this.paymentGateway = paymentGateway;
        this.executor = executor;
        this.outcomeHandler = outcomeHandler;
    }

    @Override
    public void onResourceReserved(O order, PaymentRequest request, String correlationId) {
        executor.execute(() -> chargeAndDispatch(order, request));
    }

    private void chargeAndDispatch(O order, PaymentRequest request) {
        String orderId = order.getOrderId();
        PaymentResult result;
        try {
            result = paymentGateway.charge(request);
        } catch (Exception e) {
            log.error("[AutoCharge] gateway.charge() throw cho orderId={}: {}",
                    orderId, e.getMessage(), e);
            result = PaymentResult.unknown(orderId);
        }
        try {
            outcomeHandler.accept(orderId, result);
        } catch (Exception e) {
            // Outcome handler thrown — chi log; saga state van con trong Redis,
            // reconciliation cycle sau se hoi lai.
            log.error("[AutoCharge] outcomeHandler throw cho orderId={}: {}",
                    orderId, e.getMessage(), e);
        }
    }
}
