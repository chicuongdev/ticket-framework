package io.hrc.reconciliation.order;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentResult;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.model.PaymentVerificationResult;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Xử lý Case 1 (STALE_PENDING) và Case 2 (LATE_PAYMENT_SUCCESS) bằng cách
 * query payment gateway để verify kết quả của stale pending orders.
 *
 * <p>Hai kịch bản được giải quyết:
 * <pre>
 *   Tình huống A — Gateway lỗi:
 *     charge() timeout → TimeoutHandler hết attempts → order PENDING
 *     queryStatus() → FAILED hoặc UNKNOWN → cancel + release inventory
 *
 *   Tình huống B — Response mất:
 *     charge() thành công nhưng response bị mất → order PENDING (hoặc CANCELLED)
 *     queryStatus() → SUCCESS → confirm order (hoặc refund nếu đã cancel)
 * </pre>
 *
 * <p>OrderReconciler chỉ chịu trách nhiệm <b>verify</b> (gọi queryStatus).
 * Hành động xử lý tiếp theo ({@code handleTimeout}, {@code handleLatePaymentSuccess})
 * do developer implement trong {@link io.hrc.reconciliation.AbstractReconciliationService}.
 *
 * @param <O> kiểu order cụ thể của developer (extends AbstractOrder)
 */
@Slf4j
public class OrderReconciler<O extends AbstractOrder> {

    private final PaymentGateway paymentGateway;
    private final ReconciliationMetrics metrics;

    public OrderReconciler(PaymentGateway paymentGateway, ReconciliationMetrics metrics) {
        this.paymentGateway = paymentGateway;
        this.metrics = metrics;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Verify danh sách stale orders với payment gateway.
     *
     * <p>Mỗi order được verify độc lập — lỗi 1 order không dừng toàn bộ.
     *
     * @param staleOrders   danh sách order cần verify
     * @param transactionIdExtractor function lấy transactionId từ order
     *                               (thường là orderId hoặc field riêng của developer)
     * @return danh sách kết quả verify, theo thứ tự tương ứng với input
     */
    public List<PaymentVerificationResult> reconcile(
            List<O> staleOrders,
            java.util.function.Function<O, String> transactionIdExtractor) {

        List<PaymentVerificationResult> results = new ArrayList<>(staleOrders.size());
        for (O order : staleOrders) {
            String transactionId = transactionIdExtractor.apply(order);
            results.add(verify(order, transactionId));
        }
        return results;
    }

    /**
     * Verify một order cụ thể với payment gateway.
     *
     * <p>Gọi {@code paymentGateway.queryStatus(transactionId)} và wrap kết quả.
     * Exception từ gateway được bắt — trả về {@code verified=false} thay vì throw.
     *
     * @param order         order cần verify
     * @param transactionId ID giao dịch để query gateway
     * @return kết quả verify với đủ thông tin để quyết định action tiếp theo
     */
    public PaymentVerificationResult verify(O order, String transactionId) {
        String orderId = order.getOrderId();
        log.debug("[OrderReconciler] Verifying orderId={}, transactionId={}", orderId, transactionId);

        try {
            PaymentResult result = paymentGateway.queryStatus(transactionId);

            log.info("[OrderReconciler] orderId={} payment status: {}", orderId, result.getStatus());

            return PaymentVerificationResult.builder()
                    .orderId(orderId)
                    .transactionId(transactionId)
                    .paymentResult(result)
                    .verified(true)
                    .build();

        } catch (Exception e) {
            log.error("[OrderReconciler] Failed to query gateway for orderId={}, transactionId={}: {}",
                    orderId, transactionId, e.getMessage(), e);

            return PaymentVerificationResult.builder()
                    .orderId(orderId)
                    .transactionId(transactionId)
                    .paymentResult(null)
                    .verified(false)
                    .build();
        }
    }
}
