package io.hrc.product.order.payment;

import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.AuthorizationResult;
import io.hrc.payment.model.GatewayHealth;
import io.hrc.payment.model.PaymentRequest;
import io.hrc.payment.model.PaymentResult;
import io.hrc.payment.model.RefundRequest;
import io.hrc.payment.model.RefundResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;

/**
 * PaymentGateway của ms-order — proxy HTTP sang ms-payment (service DUY NHẤT tích hợp
 * cổng thanh toán). ms-order không còn gateway local.
 *
 * <p>Dùng chung cho cả 3 chiến lược:
 * <ul>
 *   <li><b>P1/P2 sync</b>: saga gọi {@link #charge} → {@code POST /payments}, chờ kết quả.</li>
 *   <li><b>P3 async</b>: saga KHÔNG gọi charge (charge qua Kafka ở ms-payment); chỉ
 *       reconciliation gọi {@link #queryStatus} → {@code GET /payments/{id}}.</li>
 *   <li><b>Compensation</b>: {@link #refund} → {@code POST /payments/{id}/refund}.</li>
 * </ul>
 *
 * <p>{@link #charge} chống Tình huống B: nếu {@code POST /payments} timeout (không rõ
 * cổng đã trừ tiền chưa) → poll {@code GET /payments/{id}} vài lần trước khi trả UNKNOWN,
 * tránh huỷ oan order đã charge thành công.
 */
@Slf4j
public class RemotePaymentGateway implements PaymentGateway {

    private static final int TIMEOUT_POLL_ATTEMPTS = 3;
    private static final long TIMEOUT_POLL_INTERVAL_MS = 1_000;

    private final RestClient restClient;

    public RemotePaymentGateway(String msPaymentBaseUrl) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(2_000);
        rf.setReadTimeout(8_000);
        this.restClient = RestClient.builder()
                .baseUrl(msPaymentBaseUrl)
                .requestFactory(rf)
                .build();
    }

    @Override
    public PaymentResult charge(PaymentRequest request) {
        String orderId = request.getTransactionId();
        ChargeRequest body = new ChargeRequest(
                orderId,
                request.getMetadata().get("resourceId"),
                BigDecimal.valueOf(request.getAmount()),
                request.getCurrency());
        try {
            PaymentResultDto dto = restClient.post()
                    .uri("/payments")
                    .body(body)
                    .retrieve()
                    .body(PaymentResultDto.class);
            return toResult(orderId, dto);
        } catch (RestClientException e) {
            // POST không rõ kết quả (timeout/lỗi mạng) → poll status để tránh huỷ oan
            // order mà thực ra đã charge thành công (Tình huống B).
            log.warn("[RemoteGateway] POST /payments lỗi orderId={}: {} — poll status",
                    orderId, e.getMessage());
            return pollStatus(orderId);
        }
    }

    @Override
    public PaymentResult queryStatus(String transactionId) {
        try {
            PaymentResultDto dto = restClient.get()
                    .uri("/payments/{id}", transactionId)
                    .retrieve()
                    .body(PaymentResultDto.class);
            return toResult(transactionId, dto);
        } catch (RestClientException e) {
            log.debug("[RemoteGateway] GET /payments/{} lỗi: {}", transactionId, e.getMessage());
            return PaymentResult.unknown(transactionId);
        }
    }

    @Override
    public RefundResult refund(RefundRequest request) {
        String orderId = request.getTransactionId();
        try {
            RefundResultDto dto = restClient.post()
                    .uri("/payments/{id}/refund", orderId)
                    .body(new RefundRequestBody(request.getAmount(), request.getReason()))
                    .retrieve()
                    .body(RefundResultDto.class);
            if (dto == null) {
                return RefundResult.unknown(orderId);
            }
            return switch (dto.status()) {
                case "SUCCESS" -> RefundResult.success(dto.refundId(), orderId, dto.refundedAmount());
                case "FAILED" -> RefundResult.failed(orderId, dto.errorCode(), dto.errorMessage());
                case "PENDING" -> RefundResult.pending(dto.refundId(), orderId);
                default -> RefundResult.unknown(orderId);
            };
        } catch (RestClientException e) {
            log.error("[RemoteGateway] POST refund lỗi orderId={}: {}", orderId, e.getMessage());
            return RefundResult.unknown(orderId);
        }
    }

    @Override
    public RefundResult partialRefund(String transactionId, long amount) {
        return refund(RefundRequest.builder()
                .transactionId(transactionId)
                .amount(amount)
                .reason("Partial refund")
                .build());
    }

    // Pre-authorization: ms-payment chưa hỗ trợ.
    @Override
    public AuthorizationResult preAuthorize(PaymentRequest request) {
        throw new UnsupportedOperationException("ms-payment không hỗ trợ pre-authorization");
    }

    @Override
    public PaymentResult capture(String authorizationId) {
        throw new UnsupportedOperationException("ms-payment không hỗ trợ capture");
    }

    @Override
    public void voidAuthorization(String authorizationId) {
        throw new UnsupportedOperationException("ms-payment không hỗ trợ void authorization");
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public GatewayHealth getHealth() {
        return GatewayHealth.up(1.0, 0);
    }

    @Override
    public String getGatewayName() {
        return "ms-payment-remote";
    }

    // ----- helpers -----

    private PaymentResult pollStatus(String orderId) {
        for (int i = 1; i <= TIMEOUT_POLL_ATTEMPTS; i++) {
            try {
                Thread.sleep(TIMEOUT_POLL_INTERVAL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            PaymentResult r = queryStatus(orderId);
            if (r.isResolved()) {
                log.info("[RemoteGateway] poll orderId={} -> {}", orderId, r.getStatus());
                return r;
            }
        }
        return PaymentResult.unknown(orderId);
    }

    private PaymentResult toResult(String orderId, PaymentResultDto dto) {
        if (dto == null) {
            return PaymentResult.unknown(orderId);
        }
        return switch (dto.status()) {
            case "SUCCESS" -> PaymentResult.success(orderId, dto.gatewayTransactionId(), dto.amount());
            case "FAILED" -> PaymentResult.failed(orderId,
                    dto.errorCode() != null ? dto.errorCode() : "PAYMENT_FAILED",
                    dto.errorMessage());
            case "TIMEOUT" -> PaymentResult.timeout(orderId);
            default -> PaymentResult.unknown(orderId);
        };
    }

    // ----- DTO khớp PaymentController của ms-payment -----

    record ChargeRequest(String orderId, String resourceId, BigDecimal amount, String currency) {
    }

    record RefundRequestBody(long amount, String reason) {
    }

    record PaymentResultDto(String orderId, String status, String gatewayTransactionId,
                            long amount, String errorCode, String errorMessage) {
    }

    record RefundResultDto(String orderId, String status, String refundId,
                           long refundedAmount, String errorCode, String errorMessage) {
    }
}
