package io.hrc.inventory.persistence;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;

/**
 * Định kỳ xóa các entry trong bảng {@code hcr_processed_events} cũ hơn
 * retention duration để tránh bảng phình to vô hạn (edge case [EA1]).
 *
 * <h3>Vấn đề</h3>
 * Bảng dedup tăng tuyến tính theo throughput. Sau vài tháng, query
 * {@code INSERT ... ON CONFLICT} chậm dần do index lớn → consumer chậm
 * → backlog tích tụ.
 *
 * <h3>Trade-off retention</h3>
 * <ul>
 *   <li><b>Quá ngắn</b>: nếu broker resend duplicate sau retention window,
 *       sẽ bị xử lý 2 lần (vì entry dedup đã bị xóa).</li>
 *   <li><b>Quá dài</b>: bảng phình, query chậm, tốn dung lượng.</li>
 * </ul>
 *
 * <p>Default <b>7 ngày</b> — lớn hơn maximum retry window thực tế của
 * hầu hết broker (Kafka default log retention 7 ngày, RabbitMQ DLX retry
 * thường vài giờ).
 *
 * <h3>Yêu cầu</h3>
 * Ứng dụng PHẢI bật {@code @EnableScheduling} ở entry-point class để
 * Spring kích hoạt {@link Scheduled} trên bean này.
 *
 * <h3>Schedule</h3>
 * Dùng {@code fixedDelay} (không phải {@code fixedRate}) để đảm bảo các
 * lần chạy không overlap khi DELETE chạy lâu trên bảng lớn.
 *
 * @see ProcessedEvent
 * @see ProcessedEventRepository
 */
@Slf4j
public class ProcessedEventsCleanupJob {

    private final ProcessedEventRepository repository;
    private final Duration retention;

    public ProcessedEventsCleanupJob(ProcessedEventRepository repository, Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    /**
     * Wrapper được {@code @Scheduled} kích hoạt. Bắt mọi exception để
     * tránh scheduler tự động dừng khi gặp lỗi tạm thời.
     *
     * <p>Interval và initial delay configurable qua property:
     * <ul>
     *   <li>{@code hcr.event-bus.processed-events.cleanup-interval-ms} (default: 1 giờ)</li>
     *   <li>{@code hcr.event-bus.processed-events.cleanup-initial-delay-ms} (default: 60s)</li>
     * </ul>
     */
    @Scheduled(
            fixedDelayString = "${hcr.event-bus.processed-events.cleanup-interval-ms:3600000}",
            initialDelayString = "${hcr.event-bus.processed-events.cleanup-initial-delay-ms:60000}"
    )
    public void scheduledCleanup() {
        try {
            cleanup();
        } catch (Exception e) {
            log.error("[HCR] ProcessedEventsCleanupJob: cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Cleanup invocation — gọi trực tiếp từ test harness hoặc admin endpoint.
     *
     * @return số rows đã xóa
     */
    public int cleanup() {
        Instant cutoff = Instant.now().minus(retention);
        long startNanos = System.nanoTime();
        int deleted = repository.deleteByProcessedAtBefore(cutoff);
        long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;

        if (deleted > 0) {
            log.info("[HCR] ProcessedEventsCleanupJob: deleted {} rows older than {} (took {}ms)",
                    deleted, cutoff, durationMs);
        } else {
            log.debug("[HCR] ProcessedEventsCleanupJob: no rows to delete (cutoff={}, took {}ms)",
                    cutoff, durationMs);
        }
        return deleted;
    }

    public Duration getRetention() {
        return retention;
    }
}
