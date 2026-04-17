package io.hrc.observability;

import io.hrc.core.enums.FailureReason;
import io.hrc.inventory.metrics.InventoryMetrics;
import io.hrc.reconciliation.ReconciliationCase;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.model.ReconciliationResult;

/**
 * Unified metrics contract for the entire HCR framework.
 *
 * <p>Extends per-module contracts ({@link InventoryMetrics}, {@link ReconciliationMetrics})
 * so a single {@link io.hrc.observability.micrometer.MicrometerFrameworkMetrics} bean can be
 * injected everywhere — inventory strategies, saga orchestrators, reconciliation service, etc.
 *
 * <p><b>Metric groups:</b>
 * <ul>
 *   <li>Inventory (8) — inherited from {@link InventoryMetrics}</li>
 *   <li>Reconciliation (3) — inherited from {@link ReconciliationMetrics}</li>
 *   <li>Saga (4) — defined here</li>
 *   <li>Payment (5) — defined here</li>
 *   <li>Event Bus (3) — defined here</li>
 *   <li>Gateway (4) — defined here</li>
 * </ul>
 * Total: 27 metrics.
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Inject FrameworkMetrics where needed:
 * private final InventoryMetrics metrics; // ← inject MicrometerFrameworkMetrics bean here
 *
 * // When Micrometer not needed (tests, no observability):
 * FrameworkMetrics noop = FrameworkMetrics.NO_OP;
 * }</pre>
 */
public interface FrameworkMetrics extends InventoryMetrics, ReconciliationMetrics {

    // -------------------------------------------------------------------------
    // Saga metrics
    // -------------------------------------------------------------------------

    /**
     * Ghi nhận khi một saga transaction bắt đầu (sau khi pass validation).
     *
     * <p>Metric: {@code hcr_saga_started_total} (counter, tag: resource_id)
     */
    void recordSagaStarted(String resourceId);

    /**
     * Ghi nhận khi saga hoàn thành thành công (order CONFIRMED).
     *
     * <p>Metric: {@code hcr_saga_duration_ms} (timer, tags: resource_id, outcome=confirmed)
     *
     * @param durationMs thời gian từ khi saga bắt đầu đến khi CONFIRMED (ms)
     */
    void recordSagaConfirmed(String resourceId, long durationMs);

    /**
     * Ghi nhận khi saga bị huỷ (order CANCELLED).
     *
     * <p>Metric: {@code hcr_saga_cancelled_total} (counter, tags: resource_id, reason)
     *
     * @param reason lý do huỷ — thường là {@link FailureReason#name()} hoặc custom string
     */
    void recordSagaCancelled(String resourceId, String reason);

    /**
     * Ghi nhận khi saga đã rollback thành công (compensation hoàn tất).
     *
     * <p>Metric: {@code hcr_saga_compensated_total} (counter, tags: resource_id, reason)
     */
    void recordSagaCompensated(String resourceId, String reason);

    // -------------------------------------------------------------------------
    // Payment metrics
    // -------------------------------------------------------------------------

    /**
     * Ghi nhận một lần thử charge (kể cả thất bại).
     *
     * <p>Metric: {@code hcr_payment_attempts_total} (counter, tag: gateway)
     */
    void recordPaymentAttempt(String gateway);

    /**
     * Ghi nhận thanh toán thành công.
     *
     * <p>Metric: {@code hcr_payment_duration_ms} (timer, tags: gateway, status=success)
     *
     * @param durationMs thời gian từ khi gọi charge() đến khi nhận SUCCESS (ms)
     */
    void recordPaymentSuccess(String gateway, long durationMs);

    /**
     * Ghi nhận thanh toán thất bại (gateway từ chối).
     *
     * <p>Metric: {@code hcr_payment_failures_total} (counter, tags: gateway, error_code)
     */
    void recordPaymentFailure(String gateway, String errorCode);

    /**
     * Ghi nhận thanh toán timeout — gateway không phản hồi.
     *
     * <p>Metric: {@code hcr_payment_timeouts_total} (counter, tag: gateway)
     */
    void recordPaymentTimeout(String gateway);

    /**
     * Ghi nhận kết quả thanh toán không xác định — TimeoutHandler cũng không giải quyết được.
     *
     * <p>Metric: {@code hcr_payment_unknown_total} (counter, tag: gateway)
     */
    void recordPaymentUnknown(String gateway);

    // -------------------------------------------------------------------------
    // Event Bus metrics
    // -------------------------------------------------------------------------

    /**
     * Ghi nhận sau khi publish event thành công lên broker.
     *
     * <p>Metric: {@code hcr_event_published_total} (counter, tags: event_type, adapter)
     *
     * @param adapter tên adapter: "kafka", "rabbitmq", "redis-stream", "in-memory"
     */
    void recordEventPublished(String eventType, String adapter);

    /**
     * Ghi nhận sau khi consumer xử lý xong event.
     *
     * <p>Metric: {@code hcr_event_consumed_duration_ms} (timer, tag: event_type)
     *
     * @param durationMs thời gian từ khi consumer nhận đến khi ack (ms)
     */
    void recordEventConsumed(String eventType, long durationMs);

    /**
     * Ghi nhận khi consumer xử lý event thất bại (trước khi đưa vào DLQ).
     *
     * <p>Metric: {@code hcr_event_failed_total} (counter, tags: event_type, reason)
     */
    void recordEventFailed(String eventType, String reason);

    // -------------------------------------------------------------------------
    // Gateway metrics
    // -------------------------------------------------------------------------

    /**
     * Ghi nhận mỗi request vào gateway (trước khi validate).
     *
     * <p>Metric: {@code hcr_request_received_total} (counter, tag: endpoint)
     */
    void recordRequestReceived(String endpoint);

    /**
     * Ghi nhận request xử lý thành công (saga confirmed).
     *
     * <p>Metric: {@code hcr_request_duration_ms} (timer, tags: endpoint, outcome=success)
     *
     * @param durationMs thời gian end-to-end từ nhận request đến response (ms)
     */
    void recordRequestSuccess(String endpoint, long durationMs);

    /**
     * Ghi nhận request bị từ chối — rate limit, validation fail, hoặc circuit breaker open.
     *
     * <p>Metric: {@code hcr_request_rejected_total} (counter, tags: endpoint, reason)
     *
     * @param reason "rate_limit" | "validation" | "circuit_breaker" | custom string
     */
    void recordRequestRejected(String endpoint, String reason);

    /**
     * Ghi nhận khi idempotency cache hit — request trùng đã được xử lý trước đó.
     *
     * <p>Metric: {@code hcr_idempotency_hit_total} (counter, tag: endpoint)
     */
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
