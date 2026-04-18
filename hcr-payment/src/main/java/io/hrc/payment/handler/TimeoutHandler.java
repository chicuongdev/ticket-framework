package io.hrc.payment.handler;

import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentResult;
import io.hrc.payment.model.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xử lý khi {@code charge()} timeout — gateway không phản hồi trong thời gian quy định.
 *
 * <p>Giải quyết 2 tình huống nguy hiểm:
 * <ul>
 *   <li><b>Tình huống A</b> — Gateway crash, không trả response.
 *       Nếu assume FAILED: mất tiền khách (nếu thực ra đã trừ).
 *       TimeoutHandler polling → phát hiện đã charge → trả SUCCESS.</li>
 *   <li><b>Tình huống B</b> — Gateway thành công nhưng response bị mất (network partition).
 *       Nếu assume FAILED: khách mất tiền nhưng không có vé.
 *       TimeoutHandler polling → tìm lại kết quả → trả SUCCESS.</li>
 * </ul>
 *
 * <p>Cơ chế:
 * <pre>
 * charge() timeout
 *   → handle(transactionId):
 *     for attempt = 1..maxPollingAttempts:
 *       sleep(pollingIntervalMs)
 *       result = gateway.queryStatus(transactionId)
 *       if result.isResolved() → return result
 *     → hết attempts: return UNKNOWN
 *       → Saga trả 202 PENDING → Reconciliation xử lý sau
 * </pre>
 */
public class TimeoutHandler {

    private static final Logger log = LoggerFactory.getLogger(TimeoutHandler.class);

    private static final long DEFAULT_POLLING_INTERVAL_MS = 5_000;
    private static final int DEFAULT_MAX_POLLING_ATTEMPTS = 6;
    private static final int DEFAULT_SCHEDULER_POOL_SIZE = 4;

    private final PaymentGateway gateway;

    /** Khoảng cách giữa các lần poll (ms). Default: 5000ms. */
    private final long pollingIntervalMs;

    /** Số lần poll tối đa. Default: 6 (= 30 giây tổng). */
    private final int maxPollingAttempts;

    /**
     * Shared scheduler pool cho tất cả async polling task.
     * Dùng chung để tránh tạo thread mới cho mỗi timeout — chống thread explosion dưới tải cao.
     */
    private final ScheduledExecutorService scheduler;

    public TimeoutHandler(PaymentGateway gateway) {
        this(gateway, DEFAULT_POLLING_INTERVAL_MS, DEFAULT_MAX_POLLING_ATTEMPTS);
    }

    public TimeoutHandler(PaymentGateway gateway, long pollingIntervalMs, int maxPollingAttempts) {
        this(gateway, pollingIntervalMs, maxPollingAttempts, DEFAULT_SCHEDULER_POOL_SIZE);
    }

    public TimeoutHandler(PaymentGateway gateway, long pollingIntervalMs, int maxPollingAttempts,
                          int schedulerPoolSize) {
        this.gateway = gateway;
        this.pollingIntervalMs = pollingIntervalMs;
        this.maxPollingAttempts = maxPollingAttempts;
        this.scheduler = Executors.newScheduledThreadPool(schedulerPoolSize, namedDaemonFactory());
    }

    private static ThreadFactory namedDaemonFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "payment-timeout-poller-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Shutdown scheduler — được gọi khi bean bị destroy bởi Spring.
     * Các polling task đang chạy sẽ complete với UNKNOWN sau khi bị interrupt.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Polling đồng bộ — block thread cho đến khi tìm được kết quả hoặc hết attempts.
     *
     * @param transactionId ID giao dịch cần query
     * @return PaymentResult với status SUCCESS/FAILED (đã tìm được) hoặc UNKNOWN (hết attempts)
     */
    public PaymentResult handle(String transactionId) {
        log.warn("Payment timeout detected for transaction {}. Starting polling ({} attempts, {}ms interval).",
                transactionId, maxPollingAttempts, pollingIntervalMs);

        for (int attempt = 1; attempt <= maxPollingAttempts; attempt++) {
            try {
                Thread.sleep(pollingIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Polling interrupted for transaction {}. Returning UNKNOWN.", transactionId);
                return PaymentResult.unknown(transactionId);
            }

            try {
                PaymentResult result = gateway.queryStatus(transactionId);
                log.info("Polling attempt {}/{} for transaction {}: status = {}",
                        attempt, maxPollingAttempts, transactionId, result.getStatus());

                if (result.isResolved()) {
                    log.info("Transaction {} resolved after {} polling attempt(s): {}",
                            transactionId, attempt, result.getStatus());
                    return result;
                }
            } catch (Exception e) {
                log.warn("Polling attempt {}/{} failed for transaction {}: {}",
                        attempt, maxPollingAttempts, transactionId, e.getMessage());
            }
        }

        log.error("Transaction {} still UNKNOWN after {} polling attempts. Delegating to Reconciliation.",
                transactionId, maxPollingAttempts);
        return PaymentResult.unknown(transactionId);
    }

    /**
     * Polling bất đồng bộ — không block calling thread.
     * Dùng cho AsynchronousSagaOrchestrator.
     *
     * @param transactionId ID giao dịch cần query
     * @return CompletableFuture chứa kết quả polling
     */
    public CompletableFuture<PaymentResult> handleAsync(String transactionId) {
        CompletableFuture<PaymentResult> future = new CompletableFuture<>();

        log.warn("Payment timeout detected for transaction {}. Starting async polling.", transactionId);

        scheduler.schedule(
                new PollingTask(gateway, transactionId, 1, maxPollingAttempts,
                        pollingIntervalMs, future, scheduler),
                pollingIntervalMs, TimeUnit.MILLISECONDS
        );

        return future;
    }

    /**
     * Recursive polling task cho async mode.
     */
    private record PollingTask(
            PaymentGateway gateway,
            String transactionId,
            int currentAttempt,
            int maxAttempts,
            long intervalMs,
            CompletableFuture<PaymentResult> future,
            ScheduledExecutorService scheduler
    ) implements Runnable {

        private static final Logger log = LoggerFactory.getLogger(PollingTask.class);

        @Override
        public void run() {
            try {
                PaymentResult result = gateway.queryStatus(transactionId);
                log.info("Async polling attempt {}/{} for transaction {}: status = {}",
                        currentAttempt, maxAttempts, transactionId, result.getStatus());

                if (result.isResolved()) {
                    log.info("Transaction {} resolved async after {} attempt(s): {}",
                            transactionId, currentAttempt, result.getStatus());
                    future.complete(result);
                    return;
                }
            } catch (Exception e) {
                log.warn("Async polling attempt {}/{} failed for transaction {}: {}",
                        currentAttempt, maxAttempts, transactionId, e.getMessage());
            }

            if (currentAttempt >= maxAttempts) {
                log.error("Transaction {} still UNKNOWN after {} async polling attempts.", transactionId, maxAttempts);
                future.complete(PaymentResult.unknown(transactionId));
                return;
            }

            // Schedule lần poll tiếp theo
            scheduler.schedule(
                    new PollingTask(gateway, transactionId, currentAttempt + 1,
                            maxAttempts, intervalMs, future, scheduler),
                    intervalMs, TimeUnit.MILLISECONDS
            );
        }
    }
}
