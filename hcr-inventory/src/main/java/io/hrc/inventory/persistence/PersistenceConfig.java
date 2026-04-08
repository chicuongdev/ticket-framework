package io.hrc.inventory.persistence;

import lombok.Builder;
import lombok.Getter;

/**
 * Cấu hình cho DB sync consumer (P3 only).
 *
 * <p>Map từ YAML:
 * <pre>
 * hcr:
 *   inventory:
 *     persistence:
 *       mode: batch              # single (default) | batch
 *       batch-size: 500          # flush khi buffer đạt N events (default: 500)
 *       flush-interval-ms: 1000  # flush theo interval dù chưa đủ batch-size (default: 1000)
 * </pre>
 */
@Getter
@Builder
public class PersistenceConfig {

    private static final PersistenceMode DEFAULT_MODE = PersistenceMode.SINGLE;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final long DEFAULT_FLUSH_INTERVAL_MS = 1000;

    @Builder.Default
    private final PersistenceMode mode = DEFAULT_MODE;

    @Builder.Default
    private final int batchSize = DEFAULT_BATCH_SIZE;

    @Builder.Default
    private final long flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;

    public static PersistenceConfig defaults() {
        return PersistenceConfig.builder().build();
    }
}
