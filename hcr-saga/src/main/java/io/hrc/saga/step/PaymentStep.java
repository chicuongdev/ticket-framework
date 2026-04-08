package io.hrc.saga.step;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.enums.FailureReason;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.payment.model.PaymentResult;
import io.hrc.payment.model.RefundRequest;
import io.hrc.saga.context.SagaContext;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * Step 2 trong Saga — thanh toan.
 *
 * <p><b>Execute:</b> goi {@link PaymentGateway#charge(PaymentRequest)}.
 * {@code AbstractPaymentGateway.charge()} la {@code final} — da xu ly
 * timeout detection + retry ben trong.
 *
 * <p><b>Compensate:</b> goi {@link PaymentGateway#refund(RefundRequest)}.
 * Chi refund khi payment da SUCCESS. Neu payment FAILED/TIMEOUT/UNKNOWN
 * → khong co gi de refund.
 *
 * <p><b>Luu y:</b> {@code refund()} KHONG retry (double refund nguy hiem
 * hon refund failed). Neu refund fail → Reconciliation xu ly.
 *
 * @param <O> kieu order cu the cua developer
 */
@Slf4j
public class PaymentStep<O extends AbstractOrder> implements SagaStep<O> {

    private final PaymentGateway paymentGateway;
    private final Function<O, PaymentRequest> paymentRequestBuilder;

    /**
     * @param paymentGateway       gateway thuc thi thanh toan
     * @param paymentRequestBuilder ham build PaymentRequest tu order —
     *                               thuong la {@code orchestrator::buildPaymentRequest}
     */
    public PaymentStep(PaymentGateway paymentGateway,
                       Function<O, PaymentRequest> paymentRequestBuilder) {
        this.paymentGateway = paymentGateway;
        this.paymentRequestBuilder = paymentRequestBuilder;
    }

    @Override
    public StepResult execute(SagaContext<O> context) {
        O order = context.getOrder();
        PaymentRequest request = paymentRequestBuilder.apply(order);
        PaymentResult result = paymentGateway.charge(request);
        context.setPaymentResult(result);

        if (result.isSuccess()) {
            log.debug("[Saga] Payment OK: orderId={}, gatewayTxId={}",
                    order.getOrderId(), result.getGatewayTransactionId());
            return StepResult.success();
        }
        if (result.isFailed()) {
            log.info("[Saga] Payment failed: orderId={}, error={}",
                    order.getOrderId(), result.getErrorMessage());
            return StepResult.failed(FailureReason.PAYMENT_FAILED, result.getErrorMessage());
        }
        if (result.isTimeout()) {
            log.warn("[Saga] Payment timeout: orderId={}", order.getOrderId());
            return StepResult.failed(FailureReason.PAYMENT_TIMEOUT,
                    "Payment gateway không phản hồi");
        }
        // UNKNOWN — Reconciliation sẽ xử lý
        log.warn("[Saga] Payment unknown: orderId={}", order.getOrderId());
        return StepResult.failed(FailureReason.PAYMENT_UNKNOWN,
                "Không rõ kết quả thanh toán — Reconciliation sẽ xử lý");
    }

    @Override
    public void compensate(SagaContext<O> context) {
        PaymentResult paymentResult = context.getPaymentResult();
        if (paymentResult == null || !paymentResult.isSuccess()) {
            return;
        }
        O order = context.getOrder();
        try {
            RefundRequest refundRequest = RefundRequest.builder()
                    .transactionId(paymentResult.getTransactionId())
                    .gatewayTransactionId(paymentResult.getGatewayTransactionId())
                    .amount(paymentResult.getAmount())
                    .reason("Saga compensation: order " + order.getOrderId())
                    .build();
            paymentGateway.refund(refundRequest);
            log.debug("[Saga] Refund OK (compensate): orderId={}", order.getOrderId());
        } catch (Exception e) {
            log.error("[Saga] Refund failed (compensate), Reconciliation will fix: orderId={}",
                    order.getOrderId(), e);
        }
    }

    @Override
    public String getStepName() {
        return "payment";
    }

    @Override
    public boolean isRetryable() {
        return true;
    }
}
