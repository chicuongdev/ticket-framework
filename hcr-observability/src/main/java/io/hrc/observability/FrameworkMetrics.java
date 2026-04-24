package io.hrc.observability;

import io.hrc.core.enums.FailureReason;
import io.hrc.eventbus.metrics.EventBusMetrics;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.payment.metrics.PaymentMetrics;
import io.hrc.reconciliation.ReconciliationCase;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.model.ReconciliationResult;
import io.hrc.saga.metrics.SagaMetrics;

/**
 * Unified metrics contract for the entire HCR framework.
 *
 * <p>Composes 5 per-module metric interfaces — một bean
 * {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics} thỏa mãn
 * tất cả contract, có thể inject vào bất kỳ module nào.
 *
 * <p><b>Metric groups:</b>
 * <ul>
 *   <li>Inventory (8) — {@link InventoryMetrics}</li>
 *   <li>Reconciliation (3) — {@link ReconciliationMetrics}</li>
 *   <li>Saga (4) — {@link SagaMetrics}</li>
 *   <li>Payment (5) — {@link PaymentMetrics}</li>
 *   <li>Event Bus (3) — {@link EventBusMetrics}</li>
 *   <li>Gateway (4) — defined here</li>
 * </ul>
 * Total: 27 metrics.
 */
public interface FrameworkMetrics extends
        InventoryMetrics,
        ReconciliationMetrics,
        SagaMetrics,
        PaymentMetrics,
        EventBusMetrics {

    // -------------------------------------------------------------------------
    // Gateway metrics (chỉ dùng trong hcr-gateway, không tách interface riêng
    // vì gateway hiện chỉ có 1 implementation)
    // -------------------------------------------------------------------------

    /** Ghi nhận mỗi request vào gateway (trước khi validate). */
    void recordRequestReceived(String endpoint);

    /**
     * Ghi nhận request xử lý thành công (saga confirmed).
     *
     * @param durationMs thời gian end-to-end từ nhận request đến response (ms)
     */
    void recordRequestSuccess(String endpoint, long durationMs);

    /**
     * Ghi nhận request bị từ chối — rate limit, validation fail, hoặc circuit breaker open.
     *
     * @param reason "rate_limit" | "validation" | "circuit_breaker" | custom string
     */
    void recordRequestRejected(String endpoint, String reason);

    /** Ghi nhận khi idempotency cache hit — request trùng đã được xử lý trước đó. */
    void recordIdempotencyHit(String endpoint);

    // -------------------------------------------------------------------------
    // NO_OP — dùng khi không cần metrics (test, dev without Micrometer)
    // -------------------------------------------------------------------------

    /** No-op instance. Thread-safe, zero allocation. */
    FrameworkMetrics NO_OP = new NoOp();

    /** @see #NO_OP */
    class NoOp implements FrameworkMetrics {

        // InventoryMetrics
        @Override public void recordReserveAttempt(String r, String s) {}
        @Override public void recordReserveSuccess(String r, String s, long d) {}
        @Override public void recordReserveFailure(String r, String s, FailureReason f) {}
        @Override public void recordReleaseSuccess(String r, String s) {}
        @Override public void recordOversellPrevented(String r) {}
        @Override public void recordLowStock(String r) {}
        @Override public void recordDepleted(String r) {}
        @Override public void updateAvailableGauge(String r, long a) {}

        // ReconciliationMetrics
        @Override public void recordReconciliationRun(ReconciliationResult result) {}
        @Override public void recordInventoryMismatch(String r, long d) {}
        @Override public void recordFixedByCase(ReconciliationCase c, int n) {}

        // Saga
        @Override public void recordSagaStarted(String r) {}
        @Override public void recordSagaConfirmed(String r, long d) {}
        @Override public void recordSagaCancelled(String r, String reason) {}
        @Override public void recordSagaCompensated(String r, String reason) {}

        // Payment
        @Override public void recordPaymentAttempt(String g) {}
        @Override public void recordPaymentSuccess(String g, long d) {}
        @Override public void recordPaymentFailure(String g, String e) {}
        @Override public void recordPaymentTimeout(String g) {}
        @Override public void recordPaymentUnknown(String g) {}

        // Event Bus
        @Override public void recordEventPublished(String t, String a) {}
        @Override public void recordEventConsumed(String t, long d) {}
        @Override public void recordEventFailed(String t, String r) {}

        // Gateway
        @Override public void recordRequestReceived(String e) {}
        @Override public void recordRequestSuccess(String e, long d) {}
        @Override public void recordRequestRejected(String e, String r) {}
        @Override public void recordIdempotencyHit(String e) {}
    }
}
