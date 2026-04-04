package io.hrc.core.enums;

/**
 * Mức độ consistency mà mỗi inventory strategy cam kết.
 *
 * <ul>
 *   <li>{@link #STRONG} — P1 (PessimisticLock), P2 (OptimisticLock):
 *       consistency window = 0ms, source of truth là DB duy nhất.</li>
 *   <li>{@link #EVENTUAL} — P3 (RedisAtomic):
 *       consistency window &lt; 1s (normal), ≤ 5 phút (worst case).
 *       Source of truth là Redis. BẮT BUỘC có Reconciliation chạy.</li>
 * </ul>
 */
public enum ConsistencyLevel {

    /**
     * Khi reserve() trả về SUCCESS, dữ liệu đã commit vào DB.
     * Mọi read sau đó đều thấy giá trị mới nhất.
     */
    STRONG,

    /**
     * Khi reserve() trả về SUCCESS, Redis đã ghi atomic.
     * DB sẽ được sync sau (thường &lt; 1s, tối đa ≤ 5 phút qua Reconciliation).
     * Developer không được hiển thị inventory count từ DB realtime.
     */
    EVENTUAL
}
