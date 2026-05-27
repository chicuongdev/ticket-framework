package io.hrc.product.payment.controller;

import io.hrc.payment.model.PaymentResult;
import io.hrc.payment.model.RefundResult;
import io.hrc.product.payment.service.PaymentProcessingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * HTTP API thanh toán của ms-payment — service DUY NHẤT tích hợp cổng thanh toán.
 *
 * <p>ms-order không còn gateway local; nó gọi vào đây qua {@code RemotePaymentGateway}:
 * <ul>
 *   <li>{@code POST /payments} — charge đồng bộ (saga sync P1/P2)</li>
 *   <li>{@code GET  /payments/{orderId}} — hỏi trạng thái (reconciliation)</li>
 *   <li>{@code POST /payments/{orderId}/refund} — hoàn tiền (saga compensation)</li>
 * </ul>
 *
 * <p>Cả P3 async (qua {@code AutoChargeInitiation} của ms-order) lẫn P1/P2 sync đều dùng
 * cùng endpoint này — khác biệt nằm ở phía gọi (background thread vs request thread).
 */
@RestController
@RequestMapping("/payments")
@Slf4j
public class PaymentController {

    private final PaymentProcessingService paymentService;

    public PaymentController(PaymentProcessingService paymentService) {
        this.paymentService = paymentService;
    }

    /** Charge đồng bộ — saga sync (P1/P2) chờ kết quả trong response. */
    @PostMapping
    public PaymentResultResponse charge(@RequestBody ChargeRequest req) {
        log.info("[ms-payment] POST /payments orderId={}, amount={} {}",
                req.orderId(), req.amount(), req.currency());
        PaymentResult result = paymentService.charge(
                req.orderId(), req.resourceId(), req.amount(), req.currency());
        return PaymentResultResponse.from(result);
    }

    /** Hỏi trạng thái — reconciliation của ms-order gọi để xác minh order kẹt. */
    @GetMapping("/{orderId}")
    public PaymentResultResponse getStatus(@PathVariable String orderId) {
        PaymentResult result = paymentService.queryStatus(orderId);
        log.info("[ms-payment] GET /payments/{} -> {}", orderId, result.getStatus());
        return PaymentResultResponse.from(result);
    }

    /** Hoàn tiền — saga compensation gọi khi cần rollback payment. */
    @PostMapping("/{orderId}/refund")
    public RefundResultResponse refund(@PathVariable String orderId,
                                       @RequestBody RefundRequestBody req) {
        RefundResult result = paymentService.refund(orderId, req.amount(), req.reason());
        log.info("[ms-payment] POST /payments/{}/refund -> {}", orderId, result.getStatus());
        return new RefundResultResponse(orderId, result.getStatus().name(),
                result.getRefundId(), result.getRefundedAmount(),
                result.getErrorCode(), result.getErrorMessage());
    }

    // ----- DTO -----

    /** Body {@code POST /payments}. */
    public record ChargeRequest(String orderId, String resourceId,
                                 BigDecimal amount, String currency) {
    }

    /** Body {@code POST /payments/{orderId}/refund}. */
    public record RefundRequestBody(long amount, String reason) {
    }

    /** status ∈ {SUCCESS, FAILED, TIMEOUT, UNKNOWN}. */
    public record PaymentResultResponse(String orderId, String status,
                                        String gatewayTransactionId, long amount,
                                        String errorCode, String errorMessage) {
        static PaymentResultResponse from(PaymentResult r) {
            return new PaymentResultResponse(r.getTransactionId(), r.getStatus().name(),
                    r.getGatewayTransactionId(), r.getAmount(),
                    r.getErrorCode(), r.getErrorMessage());
        }
    }

    /** status ∈ {SUCCESS, FAILED, PENDING, UNKNOWN}. */
    public record RefundResultResponse(String orderId, String status, String refundId,
                                        long refundedAmount, String errorCode,
                                        String errorMessage) {
    }
}
