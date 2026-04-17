package io.hrc.reconciliation.model;

import io.hrc.reconciliation.ReconciliationCase;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Tổng kết sau mỗi lần chạy reconciliation cycle.
 *
 * <p>Được build bởi {@link io.hrc.reconciliation.AbstractReconciliationService}
 * sau khi tất cả 5 case đã được xử lý. Object này là immutable sau khi build.
 *
 * <p>Dùng để:
 * <ul>
 *   <li>Ghi metrics qua {@link io.hrc.reconciliation.ReconciliationMetrics#recordReconciliationRun}</li>
 *   <li>Expose qua {@code /actuator/hcr} endpoint (hcr-autoconfigure)</li>
 *   <li>Logging: biết mỗi cycle đã scan bao nhiêu, fix bao nhiêu</li>
 * </ul>
 */
@Getter
@Builder
public class ReconciliationResult {

    /** Tổng số record đã scan trong cycle này (orders + inventory items). */
    private final int totalScanned;

    /** Số inconsistency đã được fix thành công. */
    private final int totalFixed;

    /** Số case không fix được (lỗi hoặc cần manual review). */
    private final int totalFailed;

    /**
     * Breakdown theo từng loại case.
     * Key: ReconciliationCase, Value: số lượng đã fix.
     */
    @Builder.Default
    private final Map<ReconciliationCase, Integer> fixedByCase = new EnumMap<>(ReconciliationCase.class);

    /**
     * Chi tiết lỗi của những case không fix được.
     * Empty nếu cycle chạy clean.
     */
    @Builder.Default
    private final List<String> errors = new ArrayList<>();

    /** Thời gian cycle chạy từ đầu đến khi xong. */
    private final Duration duration;

    /** Thời điểm cycle bắt đầu chạy. */
    private final Instant runAt;

    // -------------------------------------------------------------------------
    // Computed helpers
    // -------------------------------------------------------------------------

    /**
     * @return {@code true} nếu cycle gặp ít nhất 1 lỗi không fix được.
     *         Dùng để quyết định log level: ERROR vs INFO.
     */
    public boolean hasErrors() {
        return errors != null && !errors.isEmpty();
    }

    /**
     * Tỷ lệ fix thành công: {@code totalFixed / (totalFixed + totalFailed)}.
     *
     * @return giá trị trong khoảng [0.0, 1.0].
     *         Trả về {@code 1.0} nếu không có gì cần fix (clean cycle).
     */
    public double getSuccessRate() {
        int total = totalFixed + totalFailed;
        return total == 0 ? 1.0 : (double) totalFixed / total;
    }
}
