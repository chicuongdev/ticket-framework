package io.hrc.payment.metrics;

/**
 * Contract để PaymentGateway ghi metrics.
 *
 * <p>Implementation thực tế là {@code MicrometerFrameworkMetrics} trong hcr-observability.
 * Fallback: {@link #NO_OP}.
 *
 * <p>Metric naming: {@code hcr_payment_*}. Tag chính: {@code gateway}.
 */
public interface PaymentMetrics {

    /** Ghi nhận một lần thử charge (kể cả thất bại). */
    void recordPaymentAttempt(String gateway);

    /**
     * Ghi nhận thanh toán thành công.
     *
     * @param durationMs thời gian từ khi gọi charge() đến khi nhận SUCCESS (ms)
     */
    void recordPaymentSuccess(String gateway, long durationMs);

    /** Ghi nhận thanh toán thất bại (gateway từ chối). */
    void recordPaymentFailure(String gateway, String errorCode);

    /** Ghi nhận thanh toán timeout — gateway không phản hồi. */
    void recordPaymentTimeout(String gateway);

    /** Ghi nhận kết quả thanh toán không xác định — TimeoutHandler cũng không giải quyết được. */
    void recordPaymentUnknown(String gateway);

    PaymentMetrics NO_OP = new NoOpPaymentMetrics();

    class NoOpPaymentMetrics implements PaymentMetrics {
        @Override public void recordPaymentAttempt(String g) {}
        @Override public void recordPaymentSuccess(String g, long d) {}
        @Override public void recordPaymentFailure(String g, String e) {}
        @Override public void recordPaymentTimeout(String g) {}
        @Override public void recordPaymentUnknown(String g) {}
    }
}
