package io.hrc.payment.gateway.mock;

import io.hrc.payment.gateway.AbstractPaymentGateway;
import io.hrc.payment.handler.TimeoutHandler;
import io.hrc.payment.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Gateway giả lập — dùng cho testing và benchmark.
 *
 * <p>Có thể configure mọi tình huống thực tế:
 * <ul>
 *   <li>{@code successRate} — tỉ lệ thành công (default 80%)</li>
 *   <li>{@code simulatedDelayMs} — delay giả lập network latency (default 100ms)</li>
 *   <li>{@code timeoutRate} — tỉ lệ timeout (default 5%)</li>
 *   <li>{@code noResponseRate} — Tình huống A: gateway crash (default 2%)</li>
 *   <li>{@code lateSuccessRate} — Tình huống B: charge thành công nhưng response mất (default 3%)</li>
 * </ul>
 *
 * <p><b>Lưu ý:</b> MockPaymentGateway lưu kết quả vào memory map để {@code queryStatus()}
 * có thể trả về kết quả đúng — simulate hành vi thực tế của gateway.
 *
 * <pre>{@code
 * MockPaymentGateway mock = MockPaymentGateway.builder()
 *     .successRate(0.9)
 *     .timeoutRate(0.05)
 *     .simulatedDelayMs(50)
 *     .build();
 * }</pre>
 */
public class MockPaymentGateway extends AbstractPaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    private final double successRate;
    private final long simulatedDelayMs;
    private final double timeoutRate;
    private final double noResponseRate;
    private final double lateSuccessRate;

    /**
     * Lưu kết quả thực tế của từng transaction — để queryStatus() trả về đúng.
     * Key: transactionId, Value: kết quả thực tế (SUCCESS hoặc FAILED).
     */
    private final Map<String, PaymentResult> transactionLog = new ConcurrentHashMap<>();

    private MockPaymentGateway(Builder builder) {
        super(builder.timeoutHandler, builder.maxRetries, builder.timeoutMs);
        this.successRate = builder.successRate;
        this.simulatedDelayMs = builder.simulatedDelayMs;
        this.timeoutRate = builder.timeoutRate;
        this.noResponseRate = builder.noResponseRate;
        this.lateSuccessRate = builder.lateSuccessRate;
    }

    // =========================================================================
    // doCharge() — simulate các scenario
    // =========================================================================

    @Override
    protected PaymentResult doCharge(PaymentRequest request) throws Exception {
        String txId = request.getTransactionId();

        // Simulate network latency
        simulateDelay();

        double roll = ThreadLocalRandom.current().nextDouble();

        // Tình huống A: gateway crash, không trả response
        if (roll < noResponseRate) {
            // Giao dịch KHÔNG được xử lý → queryStatus sẽ trả UNKNOWN
            log.debug("[mock] Simulating no-response (Scenario A) for txId={}", txId);
            throw new SocketTimeoutException("Mock: gateway no response (Scenario A)");
        }
        roll -= noResponseRate;

        // Tình huống B: gateway charge thành công, nhưng response bị mất
        if (roll < lateSuccessRate) {
            // Giao dịch ĐÃ được xử lý thành công → queryStatus sẽ trả SUCCESS
            PaymentResult actualResult = PaymentResult.success(
                    txId, generateGatewayTxId(), request.getAmount());
            transactionLog.put(txId, actualResult);
            log.debug("[mock] Simulating late-success (Scenario B) for txId={}. " +
                    "Actual result stored, but throwing timeout.", txId);
            throw new SocketTimeoutException("Mock: response lost (Scenario B)");
        }
        roll -= lateSuccessRate;

        // Timeout đơn thuần (gateway chưa xử lý xong)
        if (roll < timeoutRate) {
            log.debug("[mock] Simulating timeout for txId={}", txId);
            throw new SocketTimeoutException("Mock: charge timeout");
        }
        roll -= timeoutRate;

        // Thành công
        double adjustedSuccessRate = successRate / (1.0 - noResponseRate - lateSuccessRate - timeoutRate);
        if (roll < adjustedSuccessRate) {
            PaymentResult result = PaymentResult.success(
                    txId, generateGatewayTxId(), request.getAmount());
            transactionLog.put(txId, result);
            return result;
        }

        // Thất bại
        PaymentResult result = PaymentResult.failed(txId, "MOCK_DECLINED", "Mock payment declined");
        transactionLog.put(txId, result);
        return result;
    }

    // =========================================================================
    // doQuery() — trả về kết quả thực tế từ transactionLog
    // =========================================================================

    @Override
    protected PaymentResult doQuery(String transactionId) {
        simulateDelay();

        PaymentResult stored = transactionLog.get(transactionId);
        if (stored != null) {
            log.debug("[mock] queryStatus() found result for txId={}: {}", transactionId, stored.getStatus());
            return stored;
        }

        // Chưa có trong log → gateway chưa xử lý (Tình huống A)
        log.debug("[mock] queryStatus() no result found for txId={}, returning UNKNOWN", transactionId);
        return PaymentResult.unknown(transactionId);
    }

    // =========================================================================
    // doRefund()
    // =========================================================================

    @Override
    protected RefundResult doRefund(RefundRequest request) {
        simulateDelay();

        String txId = request.getTransactionId();
        PaymentResult original = transactionLog.get(txId);

        if (original == null || !original.isSuccess()) {
            return RefundResult.failed(txId, "NOT_FOUND",
                    "Original transaction not found or not successful");
        }

        String refundId = "ref-" + UUID.randomUUID().toString().substring(0, 8);
        return RefundResult.success(refundId, txId, request.getAmount());
    }

    // =========================================================================
    // Health
    // =========================================================================

    @Override
    public GatewayHealth getHealth() {
        return GatewayHealth.up(successRate, simulatedDelayMs);
    }

    @Override
    public String getGatewayName() {
        return "mock";
    }

    // =========================================================================
    // Testing utilities
    // =========================================================================

    /** Lấy toàn bộ transaction log — dùng cho assertion trong test. */
    public Map<String, PaymentResult> getTransactionLog() {
        return Map.copyOf(transactionLog);
    }

    /** Xóa transaction log — reset state giữa các test. */
    public void clearTransactionLog() {
        transactionLog.clear();
    }

    /** Số giao dịch đã xử lý. */
    public int getProcessedCount() {
        return transactionLog.size();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void simulateDelay() {
        if (simulatedDelayMs > 0) {
            try {
                Thread.sleep(simulatedDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String generateGatewayTxId() {
        return "gw-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // =========================================================================
    // Builder
    // =========================================================================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double successRate = 0.80;
        private long simulatedDelayMs = 100;
        private double timeoutRate = 0.05;
        private double noResponseRate = 0.02;
        private double lateSuccessRate = 0.03;
        private TimeoutHandler timeoutHandler;
        private int maxRetries = 3;
        private long timeoutMs = 5_000;

        public Builder successRate(double rate) {
            this.successRate = rate;
            return this;
        }

        public Builder simulatedDelayMs(long delayMs) {
            this.simulatedDelayMs = delayMs;
            return this;
        }

        public Builder timeoutRate(double rate) {
            this.timeoutRate = rate;
            return this;
        }

        public Builder noResponseRate(double rate) {
            this.noResponseRate = rate;
            return this;
        }

        public Builder lateSuccessRate(double rate) {
            this.lateSuccessRate = rate;
            return this;
        }

        public Builder timeoutHandler(TimeoutHandler handler) {
            this.timeoutHandler = handler;
            return this;
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder timeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }

        public MockPaymentGateway build() {
            if (timeoutHandler == null) {
                throw new IllegalStateException("TimeoutHandler is required. " +
                        "Use .timeoutHandler(new TimeoutHandler(gateway)) or provide a mock.");
            }
            double total = noResponseRate + lateSuccessRate + timeoutRate;
            if (total > 1.0) {
                throw new IllegalArgumentException(
                        "Sum of noResponseRate + lateSuccessRate + timeoutRate must be <= 1.0, got " + total);
            }
            return new MockPaymentGateway(this);
        }
    }
}
