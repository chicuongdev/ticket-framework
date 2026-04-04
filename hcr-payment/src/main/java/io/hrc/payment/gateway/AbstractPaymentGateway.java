package io.hrc.payment.gateway;

import io.hrc.payment.handler.TimeoutHandler;
import io.hrc.payment.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

/**
 * Template Method base cho mọi payment gateway integration.
 *
 * <p>Framework xử lý tự động:
 * <ul>
 *   <li><b>Timeout detection</b> — bắt timeout exception từ {@code doCharge()},
 *       tự động delegate sang {@link TimeoutHandler} polling.</li>
 *   <li><b>Retry</b> — retry khi gặp network error (không phải business error).
 *       Chỉ retry {@code doCharge()}, KHÔNG retry {@code doRefund()} (tránh double refund).</li>
 *   <li><b>Logging</b> — log mọi call với transactionId, duration, result.</li>
 * </ul>
 *
 * <p>Developer chỉ cần implement 3 method giao tiếp thực tế với gateway cụ thể:
 * <pre>{@code
 * public class VNPayGateway extends AbstractPaymentGateway {
 *
 *     public VNPayGateway(TimeoutHandler timeoutHandler) {
 *         super(timeoutHandler, 3, 5000L);
 *     }
 *
 *     @Override
 *     protected PaymentResult doCharge(PaymentRequest request) {
 *         // gọi VNPay API...
 *     }
 *
 *     @Override
 *     protected PaymentResult doQuery(String transactionId) {
 *         // gọi VNPay query API...
 *     }
 *
 *     @Override
 *     protected RefundResult doRefund(RefundRequest request) {
 *         // gọi VNPay refund API...
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(AbstractPaymentGateway.class);

    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final long DEFAULT_TIMEOUT_MS = 5_000;

    private final TimeoutHandler timeoutHandler;

    /** Số lần retry tối đa khi gặp network error. */
    private final int maxRetries;

    /** Thời gian timeout cho mỗi lần gọi gateway (ms). */
    private final long timeoutMs;

    protected AbstractPaymentGateway(TimeoutHandler timeoutHandler) {
        this(timeoutHandler, DEFAULT_MAX_RETRIES, DEFAULT_TIMEOUT_MS);
    }

    protected AbstractPaymentGateway(TimeoutHandler timeoutHandler, int maxRetries, long timeoutMs) {
        this.timeoutHandler = timeoutHandler;
        this.maxRetries = maxRetries;
        this.timeoutMs = timeoutMs;
    }

    // =========================================================================
    // CORE — charge() với retry + timeout detection
    // =========================================================================

    @Override
    public final PaymentResult charge(PaymentRequest request) {
        String txId = request.getTransactionId();
        log.info("[{}] charge() start: txId={}, amount={} {}",
                getGatewayName(), txId, request.getAmount(), request.getCurrency());

        long startTime = System.currentTimeMillis();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                PaymentResult result = doCharge(request);
                long duration = System.currentTimeMillis() - startTime;
                log.info("[{}] charge() completed: txId={}, status={}, duration={}ms",
                        getGatewayName(), txId, result.getStatus(), duration);
                return result;

            } catch (SocketTimeoutException | TimeoutException e) {
                // Timeout → delegate sang TimeoutHandler polling
                long duration = System.currentTimeMillis() - startTime;
                log.warn("[{}] charge() timeout after {}ms: txId={}. Delegating to TimeoutHandler.",
                        getGatewayName(), duration, txId);
                return timeoutHandler.handle(txId);

            } catch (Exception e) {
                if (isNetworkError(e) && attempt < maxRetries) {
                    log.warn("[{}] charge() network error (attempt {}/{}): txId={}, error={}",
                            getGatewayName(), attempt, maxRetries, txId, e.getMessage());
                    sleepBeforeRetry(attempt);
                    continue;
                }

                // Business error hoặc hết retry → trả FAILED
                long duration = System.currentTimeMillis() - startTime;
                log.error("[{}] charge() failed after {} attempt(s), duration={}ms: txId={}, error={}",
                        getGatewayName(), attempt, duration, txId, e.getMessage());
                return PaymentResult.failed(txId, "GATEWAY_ERROR", e.getMessage());
            }
        }

        // Fallback — không nên đến đây
        return PaymentResult.failed(txId, "MAX_RETRIES_EXCEEDED",
                "Exceeded " + maxRetries + " retries");
    }

    // =========================================================================
    // CORE — queryStatus() delegates trực tiếp
    // =========================================================================

    @Override
    public final PaymentResult queryStatus(String transactionId) {
        log.debug("[{}] queryStatus(): txId={}", getGatewayName(), transactionId);
        try {
            return doQuery(transactionId);
        } catch (Exception e) {
            log.error("[{}] queryStatus() error: txId={}, error={}",
                    getGatewayName(), transactionId, e.getMessage());
            return PaymentResult.unknown(transactionId);
        }
    }

    // =========================================================================
    // CORE — refund() KHÔNG retry (tránh double refund)
    // =========================================================================

    @Override
    public final RefundResult refund(RefundRequest request) {
        String txId = request.getTransactionId();
        log.info("[{}] refund() start: txId={}, amount={}",
                getGatewayName(), txId, request.getAmount());
        try {
            RefundResult result = doRefund(request);
            log.info("[{}] refund() completed: txId={}, status={}",
                    getGatewayName(), txId, result.getStatus());
            return result;
        } catch (Exception e) {
            log.error("[{}] refund() error: txId={}, error={}",
                    getGatewayName(), txId, e.getMessage());
            return RefundResult.failed(txId, "REFUND_ERROR", e.getMessage());
        }
    }

    @Override
    public RefundResult partialRefund(String transactionId, long amount) {
        RefundRequest request = RefundRequest.builder()
                .transactionId(transactionId)
                .amount(amount)
                .reason("Partial refund")
                .build();
        return refund(request);
    }

    // =========================================================================
    // PRE-AUTHORIZATION — default: UnsupportedOperationException
    // Gateway nào hỗ trợ thì override.
    // =========================================================================

    @Override
    public AuthorizationResult preAuthorize(PaymentRequest request) {
        throw new UnsupportedOperationException(
                getGatewayName() + " does not support pre-authorization");
    }

    @Override
    public PaymentResult capture(String authorizationId) {
        throw new UnsupportedOperationException(
                getGatewayName() + " does not support capture");
    }

    @Override
    public void voidAuthorization(String authorizationId) {
        throw new UnsupportedOperationException(
                getGatewayName() + " does not support void authorization");
    }

    // =========================================================================
    // HEALTH — default implementations
    // =========================================================================

    @Override
    public boolean isAvailable() {
        try {
            GatewayHealth health = getHealth();
            return !health.isDown();
        } catch (Exception e) {
            return false;
        }
    }

    // =========================================================================
    // ABSTRACT — Developer implement phần này
    // =========================================================================

    /**
     * Gọi API charge của gateway cụ thể.
     * Throw {@link SocketTimeoutException} hoặc {@link TimeoutException} nếu timeout.
     * Throw bất kỳ Exception nào nếu network error hoặc business error.
     */
    protected abstract PaymentResult doCharge(PaymentRequest request) throws Exception;

    /**
     * Gọi API query status của gateway cụ thể.
     * Trả về kết quả thực tế: SUCCESS, FAILED, hoặc UNKNOWN nếu gateway chưa biết.
     */
    protected abstract PaymentResult doQuery(String transactionId) throws Exception;

    /**
     * Gọi API refund của gateway cụ thể.
     */
    protected abstract RefundResult doRefund(RefundRequest request) throws Exception;

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Check xem exception có phải network error (đáng retry) hay business error (không retry).
     * Subclass có thể override để customize.
     */
    protected boolean isNetworkError(Exception e) {
        return e instanceof java.io.IOException
                || e instanceof java.net.ConnectException
                || e instanceof java.net.SocketException;
    }

    private void sleepBeforeRetry(int attempt) {
        // Exponential backoff: 100ms, 200ms, 400ms...
        long backoff = 100L * (1L << (attempt - 1));
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected TimeoutHandler getTimeoutHandler() {
        return timeoutHandler;
    }

    protected long getTimeoutMs() {
        return timeoutMs;
    }
}
