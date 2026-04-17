package io.hrc.reconciliation;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.event.reconciliation.ReconciliationFixedEvent;
import io.hrc.eventbus.event.reconciliation.ReconciliationStartedEvent;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentResult;
import io.hrc.reconciliation.inventory.InventoryReconciler;
import io.hrc.reconciliation.model.PaymentVerificationResult;
import io.hrc.reconciliation.model.ReconciliationResult;
import io.hrc.reconciliation.order.OrderReconciler;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Safety net của framework — chạy ngầm định kỳ để phát hiện và sửa 5 loại inconsistency.
 *
 * <h3>5 case được xử lý:</h3>
 * <ol>
 *   <li><b>STALE_PENDING</b> — order PENDING quá lâu (payment gateway không phản hồi)</li>
 *   <li><b>LATE_PAYMENT_SUCCESS</b> — tiền đã bị trừ nhưng order bị cancel nhầm do timeout</li>
 *   <li><b>INVENTORY_MISMATCH</b> — Redis available ≠ DB (P3 only, sau Redis crash)</li>
 *   <li><b>UNPERSISTED_RESERVATION</b> — order CONFIRMED nhưng DB inventory chưa cập nhật</li>
 *   <li><b>DUPLICATE_ORDER</b> — 2+ order cùng idempotencyKey</li>
 * </ol>
 *
 * <h3>Framework tự làm:</h3>
 * <ul>
 *   <li>Chạy theo {@code @Scheduled} với fixedDelay configurable qua property</li>
 *   <li>Distributed lock (Redisson) — chỉ 1 instance chạy tại 1 thời điểm</li>
 *   <li>Phát hiện và phân loại 5 case</li>
 *   <li>Ghi metrics sau mỗi cycle</li>
 *   <li>Publish {@link ReconciliationStartedEvent} và {@link ReconciliationFixedEvent}</li>
 * </ul>
 *
 * <h3>Developer BẮT BUỘC implement:</h3>
 * <ul>
 *   <li>{@link #findStalePendingOrders(int)} — query DB lấy order PENDING quá lâu</li>
 *   <li>{@link #getPaymentTransactionId(AbstractOrder)} — lấy transactionId từ order</li>
 *   <li>{@link #handleTimeout(AbstractOrder)} — xử lý khi gateway không có kết quả</li>
 *   <li>{@link #handleLatePaymentSuccess(AbstractOrder, PaymentResult)} — xử lý khi tìm thấy payment muộn</li>
 *   <li>{@link #handleInventoryMismatch(String, long, long)} — xử lý inventory lệch</li>
 *   <li>{@link #findUnpersistedReservations()} — tìm reservation chưa persist</li>
 *   <li>{@link #handleUnpersistedReservation(AbstractOrder)} — fix reservation chưa persist</li>
 *   <li>{@link #findDuplicateOrders()} — tìm duplicate order</li>
 *   <li>{@link #handleDuplicateOrders(List)} — xử lý duplicate</li>
 * </ul>
 *
 * <h3>Developer override để customize:</h3>
 * <ul>
 *   <li>{@link #getTimeoutMinutes()} — default: 5 phút</li>
 *   <li>{@link #getScheduleDelayMs()} — default: 300,000ms (5 phút)</li>
 *   <li>{@link #getInventoryMismatchThreshold()} — default: 0 (alert only, không auto-fix)</li>
 *   <li>{@link #getResourceIdsToReconcile()} — default: empty (bắt buộc override nếu dùng P3)</li>
 * </ul>
 *
 * <h3>Schedule configuration:</h3>
 * Interval được cấu hình qua property {@code hcr.reconciliation.schedule-delay-ms} (default: 300000ms).
 * Dùng fixedDelay thay vì fixedRate để đảm bảo không overlap: cycle mới chỉ bắt đầu sau khi
 * cycle trước đã kết thúc + delay.
 *
 * <h3>Distributed lock:</h3>
 * Dùng {@code hcr:reconciliation:lock} trên Redis (Redisson) — khi deploy nhiều instance,
 * chỉ 1 instance acquire được lock. Instance không acquire được skip cycle đó (không block).
 *
 * <h3>Dependency {@code inventoryReconciler} là optional:</h3>
 * Truyền {@code null} nếu không dùng P3 để skip Case 3 (INVENTORY_MISMATCH).
 * P1/P2 users không cần InventoryReconciler vì DB là source of truth duy nhất.
 *
 * @param <O> kiểu order cụ thể của developer (extends AbstractOrder)
 */
@Slf4j
public abstract class AbstractReconciliationService<O extends AbstractOrder> {

    private static final String DISTRIBUTED_LOCK_KEY = "hcr:reconciliation:lock";

    protected final PaymentGateway paymentGateway;
    protected final EventBus eventBus;
    protected final ReconciliationMetrics metrics;

    private final OrderReconciler<O> orderReconciler;

    @Nullable
    private final InventoryReconciler inventoryReconciler;

    private final RedissonClient redissonClient;

    /**
     * Constructor đầy đủ — dùng khi cần reconcile inventory mismatch (P3).
     *
     * @param paymentGateway      dùng để queryStatus trong Case 1/2
     * @param inventoryReconciler pre-built reconciler cho Case 3 (P3 only). Null = skip Case 3.
     * @param eventBus            publish ReconciliationStartedEvent, ReconciliationFixedEvent
     * @param redissonClient      tạo distributed lock
     * @param metrics             ghi nhận metrics sau mỗi cycle
     */
    protected AbstractReconciliationService(PaymentGateway paymentGateway,
                                             @Nullable InventoryReconciler inventoryReconciler,
                                             EventBus eventBus,
                                             RedissonClient redissonClient,
                                             ReconciliationMetrics metrics) {
        this.paymentGateway = paymentGateway;
        this.inventoryReconciler = inventoryReconciler;
        this.eventBus = eventBus;
        this.redissonClient = redissonClient;
        this.metrics = metrics;
        this.orderReconciler = new OrderReconciler<>(paymentGateway, metrics);
    }

    /**
     * Constructor rút gọn cho P1/P2 — không cần InventoryReconciler.
     */
    protected AbstractReconciliationService(PaymentGateway paymentGateway,
                                             EventBus eventBus,
                                             RedissonClient redissonClient,
                                             ReconciliationMetrics metrics) {
        this(paymentGateway, null, eventBus, redissonClient, metrics);
    }

    // =========================================================================
    // MAIN ENTRY POINT — @Scheduled, final, developer không override
    // =========================================================================

    /**
     * Entry point chính — framework tự gọi theo schedule.
     *
     * <p>Dùng {@code fixedDelay} (không phải {@code fixedRate}) để đảm bảo
     * không có 2 cycle chạy overlap. Interval bắt đầu tính sau khi cycle hiện tại kết thúc.
     *
     * <p>Distributed lock đảm bảo trong môi trường multi-instance (Kubernetes, Docker Swarm...),
     * chỉ 1 instance chạy reconciliation tại 1 thời điểm.
     *
     * <p><b>Developer KHÔNG gọi phương thức này trực tiếp.</b>
     * Dùng {@link #getScheduleDelayMs()} để configure interval.
     */
    @Scheduled(fixedDelayString = "${hcr.reconciliation.schedule-delay-ms:300000}")
    public final void runReconciliation() {
        RLock lock = redissonClient.getLock(DISTRIBUTED_LOCK_KEY);

        // Không chờ — nếu instance khác đang chạy thì skip cycle này
        boolean acquired;
        try {
            acquired = lock.tryLock(0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Reconciliation] Interrupted while trying to acquire lock. Skipping cycle.");
            return;
        }

        if (!acquired) {
            log.debug("[Reconciliation] Skip cycle: lock held by another instance.");
            return;
        }

        Instant start = Instant.now();
        Accumulator acc = new Accumulator();

        try {
            log.info("[Reconciliation] Starting cycle at {}", start);
            safePublish(new ReconciliationStartedEvent("full-cycle", 0, null));

            runCase1And2(acc);
            runCase3(acc);
            runCase4(acc);
            runCase5(acc);

        } catch (Exception e) {
            log.error("[Reconciliation] Unexpected error during cycle: {}", e.getMessage(), e);
            acc.addError("Cycle aborted: " + e.getMessage());
        } finally {
            try {
                lock.unlock();
            } catch (Exception e) {
                log.warn("[Reconciliation] Failed to release lock: {}", e.getMessage());
            }
        }

        ReconciliationResult result = ReconciliationResult.builder()
                .totalScanned(acc.totalScanned)
                .totalFixed(acc.totalFixed)
                .totalFailed(acc.totalFailed)
                .fixedByCase(acc.fixedByCase)
                .errors(acc.errors)
                .duration(Duration.between(start, Instant.now()))
                .runAt(start)
                .build();

        metrics.recordReconciliationRun(result);

        if (result.hasErrors()) {
            log.error("[Reconciliation] Cycle done with errors: scanned={}, fixed={}, failed={}, " +
                    "duration={}ms, errors={}",
                    result.getTotalScanned(), result.getTotalFixed(), result.getTotalFailed(),
                    result.getDuration().toMillis(), result.getErrors());
        } else {
            log.info("[Reconciliation] Cycle done: scanned={}, fixed={}, failed={}, duration={}ms",
                    result.getTotalScanned(), result.getTotalFixed(), result.getTotalFailed(),
                    result.getDuration().toMillis());
        }
    }

    // =========================================================================
    // CASE RUNNERS — gọi từ runReconciliation()
    // =========================================================================

    /**
     * Case 1: STALE_PENDING — query gateway, cancel nếu thất bại.
     * Case 2: LATE_PAYMENT_SUCCESS — query gateway, confirm/refund nếu thành công.
     */
    private void runCase1And2(Accumulator acc) {
        try {
            List<O> staleOrders = findStalePendingOrders(getTimeoutMinutes());
            if (staleOrders == null || staleOrders.isEmpty()) {
                return;
            }

            acc.scan(staleOrders.size());
            log.info("[Reconciliation] Case 1/2: found {} stale pending orders", staleOrders.size());

            List<PaymentVerificationResult> verifications =
                    orderReconciler.reconcile(staleOrders, this::getPaymentTransactionId);

            for (int i = 0; i < staleOrders.size(); i++) {
                O order = staleOrders.get(i);
                PaymentVerificationResult verification = verifications.get(i);

                try {
                    if (verification.isPaymentSuccess()) {
                        // Case 2: Tình huống B — payment thành công muộn
                        log.warn("[Reconciliation] LATE_PAYMENT_SUCCESS: orderId={}", order.getOrderId());
                        handleLatePaymentSuccess(order, verification.getPaymentResult());
                        acc.fixed(ReconciliationCase.LATE_PAYMENT_SUCCESS);
                        safePublish(new ReconciliationFixedEvent(
                                order.getResourceId(), order.getOrderId(),
                                "late-payment-success",
                                "Payment found SUCCESS after timeout, order reconciled",
                                null));
                    } else {
                        // Case 1: Tình huống A — gateway không thành công → cancel
                        log.info("[Reconciliation] STALE_PENDING timeout: orderId={}, paymentStatus={}",
                                order.getOrderId(),
                                verification.getPaymentResult() != null
                                        ? verification.getPaymentResult().getStatus()
                                        : "UNVERIFIED");
                        handleTimeout(order);
                        acc.fixed(ReconciliationCase.STALE_PENDING);
                        safePublish(new ReconciliationFixedEvent(
                                order.getResourceId(), order.getOrderId(),
                                "stale-pending-cancelled",
                                "Stale pending order cancelled after timeout",
                                null));
                    }
                } catch (Exception e) {
                    log.error("[Reconciliation] Failed to handle stale order orderId={}: {}",
                            order.getOrderId(), e.getMessage(), e);
                    acc.addError("stale-order-" + order.getOrderId() + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Reconciliation] Error in Case 1/2: {}", e.getMessage(), e);
            acc.addError("Case1And2: " + e.getMessage());
        }
    }

    /**
     * Case 3: INVENTORY_MISMATCH — so sánh Redis vs DB, fix nếu trong ngưỡng.
     * Skip nếu inventoryReconciler là null (P1/P2 users) hoặc getResourceIdsToReconcile() empty.
     */
    private void runCase3(Accumulator acc) {
        if (inventoryReconciler == null) {
            return;
        }

        List<String> resourceIds = getResourceIdsToReconcile();
        if (resourceIds == null || resourceIds.isEmpty()) {
            return;
        }

        try {
            acc.scan(resourceIds.size());
            log.debug("[Reconciliation] Case 3: checking {} resources for inventory mismatch",
                    resourceIds.size());

            List<String> mismatched = inventoryReconciler.reconcileAll(resourceIds);

            for (String resourceId : mismatched) {
                try {
                    // Lấy delta để truyền vào developer handler
                    var delta = inventoryReconciler.compare(resourceId);
                    handleInventoryMismatch(resourceId, delta.getRedisAvailable(), delta.getDbAvailable());
                    acc.fixed(ReconciliationCase.INVENTORY_MISMATCH);
                } catch (Exception e) {
                    log.error("[Reconciliation] Error handling inventory mismatch resourceId={}: {}",
                            resourceId, e.getMessage(), e);
                    acc.addError("inventory-mismatch-" + resourceId + ": " + e.getMessage());
                }
            }

            if (!mismatched.isEmpty()) {
                metrics.recordFixedByCase(ReconciliationCase.INVENTORY_MISMATCH, mismatched.size());
            }

        } catch (Exception e) {
            log.error("[Reconciliation] Error in Case 3: {}", e.getMessage(), e);
            acc.addError("Case3: " + e.getMessage());
        }
    }

    /**
     * Case 4: UNPERSISTED_RESERVATION — order CONFIRMED nhưng DB inventory chưa cập nhật.
     */
    private void runCase4(Accumulator acc) {
        try {
            List<O> unpersisted = findUnpersistedReservations();
            if (unpersisted == null || unpersisted.isEmpty()) {
                return;
            }

            acc.scan(unpersisted.size());
            log.info("[Reconciliation] Case 4: found {} unpersisted reservations", unpersisted.size());

            for (O order : unpersisted) {
                try {
                    handleUnpersistedReservation(order);
                    acc.fixed(ReconciliationCase.UNPERSISTED_RESERVATION);
                    safePublish(new ReconciliationFixedEvent(
                            order.getResourceId(), order.getOrderId(),
                            "unpersisted-reservation-fixed",
                            "DB inventory updated for confirmed order",
                            null));
                } catch (Exception e) {
                    log.error("[Reconciliation] Failed to fix unpersisted reservation orderId={}: {}",
                            order.getOrderId(), e.getMessage(), e);
                    acc.addError("unpersisted-" + order.getOrderId() + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Reconciliation] Error in Case 4: {}", e.getMessage(), e);
            acc.addError("Case4: " + e.getMessage());
        }
    }

    /**
     * Case 5: DUPLICATE_ORDER — tìm và xử lý order trùng idempotencyKey.
     */
    private void runCase5(Accumulator acc) {
        try {
            List<List<O>> duplicateGroups = findDuplicateOrders();
            if (duplicateGroups == null || duplicateGroups.isEmpty()) {
                return;
            }

            acc.scan(duplicateGroups.size());
            log.warn("[Reconciliation] Case 5: found {} duplicate order groups", duplicateGroups.size());

            for (List<O> group : duplicateGroups) {
                if (group == null || group.isEmpty()) continue;
                try {
                    handleDuplicateOrders(group);
                    acc.fixed(ReconciliationCase.DUPLICATE_ORDER);
                    safePublish(new ReconciliationFixedEvent(
                            group.get(0).getResourceId(), group.get(0).getOrderId(),
                            "duplicate-order-resolved",
                            "Duplicate orders resolved, kept 1, cancelled rest",
                            null));
                } catch (Exception e) {
                    log.error("[Reconciliation] Failed to handle duplicate group size={}: {}",
                            group.size(), e.getMessage(), e);
                    acc.addError("duplicate-group: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[Reconciliation] Error in Case 5: {}", e.getMessage(), e);
            acc.addError("Case5: " + e.getMessage());
        }
    }

    // =========================================================================
    // ABSTRACT METHODS — developer BẮT BUỘC implement
    // =========================================================================

    /**
     * Query DB lấy danh sách order ở trạng thái PENDING và đã quá timeout.
     *
     * <p>Ví dụ (JPA):
     * <pre>{@code
     * return orderRepository.findByStatusAndUpdatedAtBefore(
     *     OrderStatus.PENDING,
     *     Instant.now().minus(timeoutMinutes, ChronoUnit.MINUTES)
     * );
     * }</pre>
     *
     * @param timeoutMinutes số phút tối đa một order được phép ở PENDING
     * @return danh sách order cần reconcile. Empty list nếu không có.
     */
    protected abstract List<O> findStalePendingOrders(int timeoutMinutes);

    /**
     * Lấy payment transaction ID từ order để gọi {@code paymentGateway.queryStatus()}.
     *
     * <p>Transaction ID thường là orderId hoặc một field riêng trong order.
     *
     * <p>Ví dụ: {@code return order.getOrderId();}
     *
     * @param order order cần lấy transactionId
     * @return transactionId để query payment gateway
     */
    protected abstract String getPaymentTransactionId(O order);

    /**
     * Xử lý khi gateway xác nhận payment KHÔNG thành công (Tình huống A).
     *
     * <p>Action cần làm:
     * <ul>
     *   <li>Cancel order (set status = CANCELLED, failureReason = PAYMENT_TIMEOUT)</li>
     *   <li>Release inventory (inventoryStrategy.release())</li>
     *   <li>Gửi notification cho user</li>
     * </ul>
     *
     * @param order order cần cancel
     */
    protected abstract void handleTimeout(O order);

    /**
     * Xử lý khi tìm thấy payment SUCCESS muộn (Tình huống B).
     *
     * <p>Đây là case nguy hiểm nhất — khách đã mất tiền nhưng không có vé.
     * Developer cần quyết định:
     * <ul>
     *   <li>Nếu inventory còn: reconfirm order, gửi vé</li>
     *   <li>Nếu inventory hết: refund ngay + gửi email xin lỗi</li>
     * </ul>
     *
     * @param order  order cần xử lý
     * @param result PaymentResult với gatewayTransactionId để refund nếu cần
     */
    protected abstract void handleLatePaymentSuccess(O order, PaymentResult result);

    /**
     * Xử lý khi phát hiện inventory mismatch (Redis ≠ DB).
     *
     * <p>Framework đã tự detect và auto-fix (nếu delta trong ngưỡng).
     * Method này cho developer thêm logic nghiệp vụ (alert, audit log, notify ops team).
     *
     * @param resourceId    resource bị lệch
     * @param redisAvailable giá trị available trong Redis
     * @param dbAvailable   giá trị available trong DB
     */
    protected abstract void handleInventoryMismatch(String resourceId,
                                                     long redisAvailable,
                                                     long dbAvailable);

    /**
     * Tìm reservation đã CONFIRMED trong saga context nhưng DB inventory chưa được cập nhật.
     *
     * <p>Cách phát hiện: tìm order CONFIRMED trong DB có updatedAt < (now - lag_window)
     * mà DB inventory vẫn chưa reflect số lượng đó.
     *
     * @return danh sách order cần fix. Empty list nếu không có.
     */
    protected abstract List<O> findUnpersistedReservations();

    /**
     * Fix một order có reservation chưa được persist xuống DB inventory.
     *
     * <p>Thường là: force update available quantity trong DB theo số lượng của order.
     *
     * @param order order cần fix
     */
    protected abstract void handleUnpersistedReservation(O order);

    /**
     * Tìm các nhóm order có cùng idempotencyKey — idempotency bị bypass.
     *
     * <p>Ví dụ (JPA):
     * <pre>{@code
     * return orderRepository.findDuplicatesByIdempotencyKey()
     *         .stream()
     *         .collect(Collectors.groupingBy(AbstractOrder::getIdempotencyKey))
     *         .values()
     *         .stream()
     *         .filter(group -> group.size() > 1)
     *         .collect(Collectors.toList());
     * }</pre>
     *
     * @return danh sách group, mỗi group là list các order cùng idempotencyKey.
     *         Empty list nếu không có duplicate.
     */
    protected abstract List<List<O>> findDuplicateOrders();

    /**
     * Xử lý một group order bị duplicate.
     *
     * <p>Logic được khuyến nghị:
     * <ul>
     *   <li>Giữ 1 order (ưu tiên CONFIRMED > RESERVED > PENDING)</li>
     *   <li>Cancel các order còn lại, release inventory</li>
     * </ul>
     *
     * @param duplicates danh sách order cùng idempotencyKey (size &ge; 2)
     */
    protected abstract void handleDuplicateOrders(List<O> duplicates);

    // =========================================================================
    // CONFIGURATION — developer override để customize
    // =========================================================================

    /**
     * Số phút tối đa một order được phép ở PENDING trước khi bị xem là stale.
     * Default: 5 phút.
     */
    protected int getTimeoutMinutes() {
        return 5;
    }

    /**
     * Interval giữa các cycle (ms). Chỉ dùng để document — schedule thực tế
     * được cấu hình qua property {@code hcr.reconciliation.schedule-delay-ms}.
     * Default: 300,000ms (5 phút).
     */
    protected long getScheduleDelayMs() {
        return 300_000L;
    }

    /**
     * Ngưỡng auto-fix inventory mismatch.
     * <ul>
     *   <li>0 (default) — chỉ alert, không auto-fix bất kỳ mismatch nào</li>
     *   <li>N &gt; 0 — auto-fix nếu delta &le; N, chỉ alert nếu delta &gt; N</li>
     * </ul>
     *
     * <p>Recommendation: bắt đầu với 0 (alert only) trong production.
     * Chỉ nâng lên sau khi đã hiểu rõ pattern mismatch trong hệ thống của bạn.
     */
    protected long getInventoryMismatchThreshold() {
        return 0L;
    }

    /**
     * Danh sách resourceId cần reconcile inventory (P3 only).
     *
     * <p>P3 users phải override method này. P1/P2 users không cần override.
     *
     * <p>Ví dụ: trả về danh sách tất cả concert đang active.
     *
     * @return danh sách resourceId. Empty list = skip Case 3.
     */
    protected List<String> getResourceIdsToReconcile() {
        return Collections.emptyList();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void safePublish(Object event) {
        try {
            eventBus.publish((io.hrc.core.domain.DomainEvent) event);
        } catch (Exception e) {
            log.warn("[Reconciliation] Failed to publish event {}: {}",
                    event.getClass().getSimpleName(), e.getMessage());
        }
    }

    // =========================================================================
    // Mutable accumulator — dùng nội bộ trong runReconciliation()
    // =========================================================================

    private static class Accumulator {
        int totalScanned = 0;
        int totalFixed = 0;
        int totalFailed = 0;
        final Map<ReconciliationCase, Integer> fixedByCase = new EnumMap<>(ReconciliationCase.class);
        final List<String> errors = new ArrayList<>();

        void scan(int count) {
            totalScanned += count;
        }

        void fixed(ReconciliationCase c) {
            totalFixed++;
            fixedByCase.merge(c, 1, Integer::sum);
        }

        void addError(String error) {
            totalFailed++;
            errors.add(error);
        }
    }
}
