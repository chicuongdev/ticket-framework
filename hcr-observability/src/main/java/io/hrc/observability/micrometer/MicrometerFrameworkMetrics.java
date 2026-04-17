package io.hrc.observability.micrometer;

import io.hrc.core.enums.FailureReason;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.reconciliation.ReconciliationCase;
import io.hrc.reconciliation.model.ReconciliationResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer implementation of {@link FrameworkMetrics}.
 *
 * <p>Auto-exports all metrics to Prometheus (or any other Micrometer-compatible registry).
 * Developer chỉ cần inject bean này — framework sẽ tự track mọi thứ.
 *
 * <p><b>Metric naming convention:</b> {@code hcr_<domain>_<action>_<unit>}
 * <br>Ví dụ: {@code hcr_reservation_duration_ms}, {@code hcr_oversell_prevented_total}.
 *
 * <p><b>Tag naming convention:</b> snake_case — {@code resource_id}, {@code event_type}.
 *
 * <p><b>Gauge pattern cho inventory available:</b>
 * Mỗi resourceId có một {@link AtomicLong} riêng, đăng ký một lần với Micrometer khi
 * {@link #updateAvailableGauge} được gọi lần đầu. Các lần sau chỉ cập nhật giá trị.
 *
 * <p>Auto-configured bởi {@code HcrAutoConfiguration} khi Micrometer trên classpath.
 */
public class MicrometerFrameworkMetrics implements FrameworkMetrics {

    private final MeterRegistry registry;

    /**
     * Lưu AtomicLong per-resourceId cho Gauge inventory available.
     * computeIfAbsent đảm bảo mỗi resourceId chỉ đăng ký Gauge một lần.
     */
    private final ConcurrentHashMap<String, AtomicLong> availableGauges = new ConcurrentHashMap<>();

    public MicrometerFrameworkMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // =========================================================================
    // InventoryMetrics
    // =========================================================================

    @Override
    public void recordReserveAttempt(String resourceId, String strategy) {
        Counter.builder("hcr_reservation_attempts_total")
               .tag("resource_id", resourceId)
               .tag("strategy", strategy)
               .description("Total reservation attempts (success + failure)")
               .register(registry)
               .increment();
    }

    @Override
    public void recordReserveSuccess(String resourceId, String strategy, long durationMs) {
        Timer.builder("hcr_reservation_duration_ms")
             .tag("resource_id", resourceId)
             .tag("strategy", strategy)
             .tag("outcome", "success")
             .description("Reservation processing duration in milliseconds")
             .register(registry)
             .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordReserveFailure(String resourceId, String strategy, FailureReason reason) {
        Counter.builder("hcr_reservation_failures_total")
               .tag("resource_id", resourceId)
               .tag("strategy", strategy)
               .tag("reason", reason.name())
               .description("Total reservation failures by reason")
               .register(registry)
               .increment();
    }

    @Override
    public void recordReleaseSuccess(String resourceId, String strategy) {
        Counter.builder("hcr_release_total")
               .tag("resource_id", resourceId)
               .tag("strategy", strategy)
               .description("Total successful inventory releases")
               .register(registry)
               .increment();
    }

    @Override
    public void recordOversellPrevented(String resourceId) {
        Counter.builder("hcr_oversell_prevented_total")
               .tag("resource_id", resourceId)
               .description("Oversell attempts rejected by framework — should always be > 0 under load")
               .register(registry)
               .increment();
    }

    @Override
    public void recordLowStock(String resourceId) {
        Counter.builder("hcr_low_stock_events_total")
               .tag("resource_id", resourceId)
               .description("Times inventory crossed below low-stock threshold")
               .register(registry)
               .increment();
    }

    @Override
    public void recordDepleted(String resourceId) {
        Counter.builder("hcr_depleted_events_total")
               .tag("resource_id", resourceId)
               .description("Times inventory reached zero (fully depleted)")
               .register(registry)
               .increment();
    }

    /**
     * Cập nhật Gauge realtime.
     *
     * <p>Pattern: {@link AtomicLong} được lưu trong {@link #availableGauges} làm state object.
     * Micrometer giữ weak reference đến nó — không leak. Thread-safe qua computeIfAbsent.
     */
    @Override
    public void updateAvailableGauge(String resourceId, long available) {
        AtomicLong gaugeValue = availableGauges.computeIfAbsent(resourceId, id -> {
            AtomicLong value = new AtomicLong(0);
            Gauge.builder("hcr_inventory_available", value, AtomicLong::get)
                 .tag("resource_id", id)
                 .description("Current available inventory units (realtime)")
                 .register(registry);
            return value;
        });
        gaugeValue.set(available);
    }

    // =========================================================================
    // ReconciliationMetrics
    // =========================================================================

    /**
     * Phát ra 3 nhóm metric từ một {@link ReconciliationResult}:
     * <ol>
     *   <li>Counter cycle run (tag: has_errors)</li>
     *   <li>Timer cycle duration</li>
     *   <li>Counter fixed per-case (delegate sang {@link #recordFixedByCase})</li>
     * </ol>
     */
    @Override
    public void recordReconciliationRun(ReconciliationResult result) {
        Counter.builder("hcr_reconciliation_run_total")
               .tag("has_errors", String.valueOf(result.hasErrors()))
               .description("Total reconciliation cycles completed")
               .register(registry)
               .increment();

        Timer.builder("hcr_reconciliation_duration_ms")
             .description("Reconciliation cycle end-to-end duration")
             .register(registry)
             .record(result.getDuration().toMillis(), TimeUnit.MILLISECONDS);

        // Breakdown fixed count per case — chỉ ghi case có count > 0
        result.getFixedByCase().forEach((reconciliationCase, count) -> {
            if (count > 0) {
                recordFixedByCase(reconciliationCase, count);
            }
        });
    }

    @Override
    public void recordInventoryMismatch(String resourceId, long delta) {
        Counter.builder("hcr_inventory_mismatch_total")
               .tag("resource_id", resourceId)
               .description("Times Redis inventory diverged from DB (P3 only)")
               .register(registry)
               .increment();

        // Lưu absolute value để distribution summary có ý nghĩa
        DistributionSummary.builder("hcr_inventory_mismatch_delta")
                           .tag("resource_id", resourceId)
                           .description("Magnitude of Redis vs DB inventory delta")
                           .register(registry)
                           .record(Math.abs(delta));
    }

    @Override
    public void recordFixedByCase(ReconciliationCase reconciliationCase, int count) {
        Counter.builder("hcr_reconciliation_fixed_total")
               .tag("case", reconciliationCase.name())
               .description("Reconciliation items fixed, broken down by case type")
               .register(registry)
               .increment(count);
    }

    // =========================================================================
    // Saga metrics
    // =========================================================================

    @Override
    public void recordSagaStarted(String resourceId) {
        Counter.builder("hcr_saga_started_total")
               .tag("resource_id", resourceId)
               .description("Total saga transactions started (post-validation)")
               .register(registry)
               .increment();
    }

    @Override
    public void recordSagaConfirmed(String resourceId, long durationMs) {
        Timer.builder("hcr_saga_duration_ms")
             .tag("resource_id", resourceId)
             .tag("outcome", "confirmed")
             .description("Saga end-to-end duration by outcome")
             .register(registry)
             .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordSagaCancelled(String resourceId, String reason) {
        Counter.builder("hcr_saga_cancelled_total")
               .tag("resource_id", resourceId)
               .tag("reason", reason)
               .description("Total saga transactions cancelled (with reason)")
               .register(registry)
               .increment();
    }

    @Override
    public void recordSagaCompensated(String resourceId, String reason) {
        Counter.builder("hcr_saga_compensated_total")
               .tag("resource_id", resourceId)
               .tag("reason", reason)
               .description("Total successful saga compensations (rollback completed)")
               .register(registry)
               .increment();
    }

    // =========================================================================
    // Payment metrics
    // =========================================================================

    @Override
    public void recordPaymentAttempt(String gateway) {
        Counter.builder("hcr_payment_attempts_total")
               .tag("gateway", gateway)
               .description("Total payment charge attempts")
               .register(registry)
               .increment();
    }

    @Override
    public void recordPaymentSuccess(String gateway, long durationMs) {
        Timer.builder("hcr_payment_duration_ms")
             .tag("gateway", gateway)
             .tag("status", "success")
             .description("Payment processing duration by status")
             .register(registry)
             .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordPaymentFailure(String gateway, String errorCode) {
        Counter.builder("hcr_payment_failures_total")
               .tag("gateway", gateway)
               .tag("error_code", errorCode)
               .description("Total payment failures by gateway and error code")
               .register(registry)
               .increment();
    }

    @Override
    public void recordPaymentTimeout(String gateway) {
        Counter.builder("hcr_payment_timeouts_total")
               .tag("gateway", gateway)
               .description("Total payment timeouts (gateway did not respond)")
               .register(registry)
               .increment();
    }

    @Override
    public void recordPaymentUnknown(String gateway) {
        Counter.builder("hcr_payment_unknown_total")
               .tag("gateway", gateway)
               .description("Total payments with unresolvable outcome (triggers reconciliation)")
               .register(registry)
               .increment();
    }

    // =========================================================================
    // Event Bus metrics
    // =========================================================================

    @Override
    public void recordEventPublished(String eventType, String adapter) {
        Counter.builder("hcr_event_published_total")
               .tag("event_type", eventType)
               .tag("adapter", adapter)
               .description("Total events successfully published to broker")
               .register(registry)
               .increment();
    }

    @Override
    public void recordEventConsumed(String eventType, long durationMs) {
        Timer.builder("hcr_event_consumed_duration_ms")
             .tag("event_type", eventType)
             .description("Event handler processing duration")
             .register(registry)
             .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordEventFailed(String eventType, String reason) {
        Counter.builder("hcr_event_failed_total")
               .tag("event_type", eventType)
               .tag("reason", reason)
               .description("Total event processing failures (before DLQ)")
               .register(registry)
               .increment();
    }

    // =========================================================================
    // Gateway metrics
    // =========================================================================

    @Override
    public void recordRequestReceived(String endpoint) {
        Counter.builder("hcr_request_received_total")
               .tag("endpoint", endpoint)
               .description("Total requests entering the gateway pipeline")
               .register(registry)
               .increment();
    }

    @Override
    public void recordRequestSuccess(String endpoint, long durationMs) {
        Timer.builder("hcr_request_duration_ms")
             .tag("endpoint", endpoint)
             .tag("outcome", "success")
             .description("Request end-to-end duration (gateway entry to saga confirmed)")
             .register(registry)
             .record(durationMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void recordRequestRejected(String endpoint, String reason) {
        Counter.builder("hcr_request_rejected_total")
               .tag("endpoint", endpoint)
               .tag("reason", reason)
               .description("Total rejected requests: rate_limit | validation | circuit_breaker")
               .register(registry)
               .increment();
    }

    @Override
    public void recordIdempotencyHit(String endpoint) {
        Counter.builder("hcr_idempotency_hit_total")
               .tag("endpoint", endpoint)
               .description("Duplicate requests served from idempotency cache")
               .register(registry)
               .increment();
    }
}
