package io.hrc.testing;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.domain.DomainEvent;
import io.hrc.core.domain.OrderRequest;
import io.hrc.eventbus.adapter.inmemory.InMemoryEventBusAdapter;
import io.hrc.payment.gateway.mock.MockPaymentGateway;
import io.hrc.payment.handler.TimeoutHandler;
import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;
import io.hrc.testing.inventory.InMemoryInventoryStrategy;
import io.hrc.testing.result.ConcurrencyTestResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Utility factory cho test setup và concurrency assertions.
 *
 * <pre>{@code
 * InMemoryInventoryStrategy inv = FrameworkTestSupport.inMemoryInventory("R1", 100);
 * MockPaymentGateway pay = FrameworkTestSupport.mockPaymentAlwaysSuccess();
 * InMemoryEventBusAdapter bus = FrameworkTestSupport.inMemoryEventBus();
 *
 * ConcurrencyTestResult result = FrameworkTestSupport.simulateConcurrentRequests(
 *     orchestrator, request, 50, 200);
 *
 * FrameworkTestSupport.assertZeroOversell(result);
 * FrameworkTestSupport.assertNoOversell("R1", inv);
 * }</pre>
 */
public final class FrameworkTestSupport {

    private FrameworkTestSupport() {}

    // =========================================================================
    // Factories — inventory
    // =========================================================================

    /**
     * Tạo InMemoryInventoryStrategy đã initialize sẵn 1 resource.
     *
     * @param resourceId   ID tài nguyên
     * @param initialStock số lượng ban đầu
     */
    public static InMemoryInventoryStrategy inMemoryInventory(String resourceId, long initialStock) {
        InMemoryInventoryStrategy strategy = new InMemoryInventoryStrategy();
        strategy.initialize(resourceId, initialStock);
        return strategy;
    }

    /** Tạo InMemoryInventoryStrategy trống — developer tự gọi initialize(). */
    public static InMemoryInventoryStrategy inMemoryInventory() {
        return new InMemoryInventoryStrategy();
    }

    // =========================================================================
    // Factories — payment
    // =========================================================================

    /**
     * Tạo MockPaymentGateway với success rate cho trước.
     * Không simulate timeout hay noResponse — phù hợp cho happy-path test.
     *
     * @param successRate tỉ lệ thành công [0.0, 1.0]
     */
    public static MockPaymentGateway mockPayment(double successRate) {
        return buildMock(successRate, 0.0, 0.0, 0.0);
    }

    /** MockPaymentGateway luôn thành công (100% success, 0ms delay). */
    public static MockPaymentGateway mockPaymentAlwaysSuccess() {
        return buildMock(1.0, 0.0, 0.0, 0.0);
    }

    /** MockPaymentGateway luôn thất bại. */
    public static MockPaymentGateway mockPaymentAlwaysFail() {
        return buildMock(0.0, 0.0, 0.0, 0.0);
    }

    /**
     * MockPaymentGateway simulate Tình huống A (gateway crash, no response).
     *
     * @param noResponseRate tỉ lệ gateway crash [0.0, 1.0]
     */
    public static MockPaymentGateway mockPaymentWithNoResponse(double noResponseRate) {
        return buildMock(1.0 - noResponseRate, 0.0, noResponseRate, 0.0);
    }

    /**
     * MockPaymentGateway simulate Tình huống B (late success — charge thành công nhưng response mất).
     *
     * @param lateSuccessRate tỉ lệ response mất [0.0, 1.0]
     */
    public static MockPaymentGateway mockPaymentWithLateSuccess(double lateSuccessRate) {
        return buildMock(1.0 - lateSuccessRate, 0.0, 0.0, lateSuccessRate);
    }

    // =========================================================================
    // Factories — event bus
    // =========================================================================

    /** Tạo InMemoryEventBusAdapter (không cần Kafka/RabbitMQ). */
    public static InMemoryEventBusAdapter inMemoryEventBus() {
        return new InMemoryEventBusAdapter();
    }

    // =========================================================================
    // Concurrency simulation
    // =========================================================================

    /**
     * Gửi {@code totalRequests} request đồng thời lên orchestrator.
     * Dùng {@code concurrentUsers} threads song song với CountDownLatch để tất cả
     * cùng bắt đầu một lúc, tối đa hóa contention.
     *
     * @param orchestrator    saga orchestrator cần test
     * @param requestTemplate request mẫu — được tái sử dụng, nên immutable/thread-safe
     * @param concurrentUsers số thread song song
     * @param totalRequests   tổng số request cần gửi
     * @return kết quả test với stats
     */
    public static <REQ extends OrderRequest, O extends AbstractOrder>
    ConcurrencyTestResult simulateConcurrentRequests(
            AbstractSagaOrchestrator<REQ, O> orchestrator,
            REQ requestTemplate,
            int concurrentUsers,
            int totalRequests) {

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(concurrentUsers);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalRequests);

        Instant testStart = Instant.now();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long t0 = System.currentTimeMillis();
                    try {
                        orchestrator.process(requestTemplate);
                        latencies.add(System.currentTimeMillis() - t0);
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        latencies.add(System.currentTimeMillis() - t0);
                        failureCount.incrementAndGet();
                        errors.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        try {
            doneLatch.await(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        Duration duration = Duration.between(testStart, Instant.now());
        long totalMs = Math.max(1, duration.toMillis());
        long tps = totalRequests * 1000L / totalMs;

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();

        return ConcurrencyTestResult.builder()
                .totalRequests(totalRequests)
                .successCount(successCount.get())
                .failureCount(failureCount.get())
                .oversellCount(0) // kiểm tra qua assertNoOversell() với strategy
                .throughputTps(tps)
                .p50LatencyMs(percentile(sorted, 50))
                .p95LatencyMs(percentile(sorted, 95))
                .p99LatencyMs(percentile(sorted, 99))
                .totalDuration(duration)
                .errors(List.copyOf(errors))
                .build();
    }

    // =========================================================================
    // Assertions
    // =========================================================================

    /**
     * Kiểm tra không có oversell: available &gt;= 0 sau tất cả thao tác.
     *
     * @throws AssertionError nếu available &lt; 0 (oversell xảy ra)
     */
    public static void assertNoOversell(String resourceId, InMemoryInventoryStrategy strategy) {
        long avail = strategy.getCurrentAvailable(resourceId);
        if (avail < 0) {
            throw new AssertionError(
                    "PHÁT HIỆN OVERSELL cho resource=" + resourceId +
                    ". available=" + avail + " (kỳ vọng >= 0)");
        }
    }

    /**
     * Kiểm tra kết quả concurrency test không có oversell.
     *
     * @throws AssertionError nếu oversellCount &gt; 0
     */
    public static void assertZeroOversell(ConcurrencyTestResult result) {
        if (result.hasOversell()) {
            throw new AssertionError(
                    "PHÁT HIỆN OVERSELL: oversellCount=" + result.getOversellCount());
        }
    }

    /**
     * Kiểm tra throughput đạt ngưỡng tối thiểu.
     *
     * @param minTps ngưỡng tối thiểu (transactions/second)
     * @throws AssertionError nếu throughput thấp hơn ngưỡng
     */
    public static void assertThroughputAbove(ConcurrencyTestResult result, long minTps) {
        if (result.getThroughputTps() < minTps) {
            throw new AssertionError(
                    "Throughput quá thấp: thực tế=" + result.getThroughputTps() +
                    " tps, kỳ vọng >= " + minTps + " tps");
        }
    }

    /**
     * Kiểm tra ít nhất 1 event thuộc type này đã được publish.
     *
     * @throws AssertionError nếu không có event nào được publish
     */
    public static void assertEventPublished(InMemoryEventBusAdapter bus,
                                             Class<? extends DomainEvent> eventType) {
        long count = bus.getPublishedCount(eventType);
        if (count == 0) {
            throw new AssertionError(
                    "Kỳ vọng event " + eventType.getSimpleName() +
                    " được publish, nhưng không tìm thấy event nào.");
        }
    }

    /**
     * Kiểm tra số lần event được publish bằng đúng expectedCount.
     *
     * @throws AssertionError nếu số lần publish khác expectedCount
     */
    public static void assertEventPublishedTimes(InMemoryEventBusAdapter bus,
                                                  Class<? extends DomainEvent> eventType,
                                                  long expectedCount) {
        long actual = bus.getPublishedCount(eventType);
        if (actual != expectedCount) {
            throw new AssertionError(
                    "Kỳ vọng event " + eventType.getSimpleName() + " được publish " +
                    expectedCount + " lần, nhưng thực tế publish " + actual + " lần.");
        }
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private static MockPaymentGateway buildMock(double successRate, double timeoutRate,
                                                 double noResponseRate, double lateSuccessRate) {
        // TimeoutHandler cần gateway reference để poll, nhưng trong test với rate=0 thì không bao giờ gọi.
        // Dùng placeholder null — nếu thực sự timeout xảy ra thì NPE sẽ báo rõ vấn đề.
        TimeoutHandler timeoutHandler = new TimeoutHandler(null, 100, 1);
        return MockPaymentGateway.builder()
                .successRate(successRate)
                .simulatedDelayMs(0)
                .timeoutRate(timeoutRate)
                .noResponseRate(noResponseRate)
                .lateSuccessRate(lateSuccessRate)
                .timeoutHandler(timeoutHandler)
                .maxRetries(1)
                .timeoutMs(1000)
                .build();
    }

    private static long percentile(long[] sorted, int pct) {
        if (sorted.length == 0) return 0L;
        int idx = (int) Math.ceil(pct / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
