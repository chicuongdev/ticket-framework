package io.hrc.inventory.persistence;

/**
 * Chế độ đồng bộ DB cho P3 (RedisAtomicStrategy).
 *
 * <p>Cấu hình qua {@code hcr.inventory.persistence.mode}:
 * <ul>
 *   <li><b>SINGLE:</b> Mỗi event = 1 transaction. Đơn giản, latency thấp cho DB sync,
 *       nhưng throughput bị giới hạn bởi số transaction/s mà DB xử lý được.</li>
 *   <li><b>BATCH:</b> Gom nhiều events cùng resourceId rồi flush 1 lần.
 *       Giảm đáng kể số transaction (vd: 1000 reserve → 1 UPDATE SET available -= 1000).
 *       Trade-off: DB lag tăng thêm (tối đa = flushIntervalMs).</li>
 * </ul>
 */
public enum PersistenceMode {
    SINGLE,
    BATCH
}
