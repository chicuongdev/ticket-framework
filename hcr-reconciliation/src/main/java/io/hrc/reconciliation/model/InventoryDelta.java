package io.hrc.reconciliation.model;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Kết quả so sánh Redis vs DB inventory cho một resource.
 *
 * <p>Được tạo bởi {@link io.hrc.reconciliation.inventory.InventoryReconciler#compare(String)}.
 * Chỉ có ý nghĩa khi dùng P3 (RedisAtomicStrategy).
 *
 * <p>Quy ước dấu của {@link #delta}:
 * <ul>
 *   <li>{@code delta > 0}: Redis có NHIỀU hơn DB — thường do Redis crash + AOF restore
 *       không đầy đủ (mất &lt; 1 giây write). Redis đếm cao hơn thực tế → NGUY HIỂM nếu
 *       không fix (có thể oversell sau Redis restart).</li>
 *   <li>{@code delta < 0}: DB có NHIỀU hơn Redis — trạng thái bình thường của P3 eventual
 *       consistency. DB chưa được sync từ EventBus consumer (lag &lt; 1s). Không cần fix.</li>
 *   <li>{@code delta == 0}: nhất quán.</li>
 * </ul>
 */
@Getter
@Builder
public class InventoryDelta {

    /** ID resource đang được so sánh. */
    private final String resourceId;

    /** Số lượng available theo Redis (source of truth P3). */
    private final long redisAvailable;

    /** Số lượng available theo DB (có thể lag so với Redis). */
    private final long dbAvailable;

    /**
     * Độ lệch = redisAvailable - dbAvailable.
     * Dương: Redis cao hơn DB (Redis có vấn đề).
     * Âm: DB cao hơn Redis (DB lag, bình thường).
     */
    private final long delta;

    /** {@code true} nếu delta != 0 — cần điều tra. */
    private final boolean hasMismatch;

    /** Thời điểm đo. */
    private final Instant measuredAt;

    // -------------------------------------------------------------------------
    // Factory helpers
    // -------------------------------------------------------------------------

    /**
     * Tạo InventoryDelta từ 2 giá trị đo được.
     * Tự tính delta và hasMismatch.
     */
    public static InventoryDelta of(String resourceId, long redisAvailable, long dbAvailable) {
        long delta = redisAvailable - dbAvailable;
        return InventoryDelta.builder()
                .resourceId(resourceId)
                .redisAvailable(redisAvailable)
                .dbAvailable(dbAvailable)
                .delta(delta)
                .hasMismatch(delta != 0)
                .measuredAt(Instant.now())
                .build();
    }

    /**
     * @return {@code true} nếu Redis có nhiều hơn DB — cần xem xét fix Redis về mức DB.
     */
    public boolean isRedisHigherThanDb() {
        return delta > 0;
    }

    /**
     * @return {@code true} nếu DB có nhiều hơn Redis — trạng thái DB-lag bình thường trong P3.
     */
    public boolean isDbHigherThanRedis() {
        return delta < 0;
    }
}
