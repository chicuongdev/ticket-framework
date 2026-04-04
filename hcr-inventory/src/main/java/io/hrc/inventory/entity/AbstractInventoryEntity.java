package io.hrc.inventory.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Base class cho entity chứa inventory — developer PHẢI extend.
 *
 * <p>Framework thao tác trực tiếp trên các field {@code available}, {@code total},
 * {@code version} của entity developer — <b>không tạo bảng riêng</b>.
 * Developer thấy đúng số lượng khi query bảng của mình.
 *
 * <p>Ba strategy P1/P2/P3 đều dùng các field này qua {@link jakarta.persistence.EntityManager}:
 * <ul>
 *   <li>P1: {@code entityManager.find(entityClass, resourceId, PESSIMISTIC_WRITE)}</li>
 *   <li>P2: {@code find() → modify available → merge() → @Version tự check}</li>
 *   <li>P3: Redis là source of truth, DB được sync async qua EventBus</li>
 * </ul>
 *
 * <p>Ví dụ developer sử dụng:
 * <pre>{@code
 * @Entity
 * @Table(name = "concert_tickets")
 * public class ConcertTicket extends AbstractInventoryEntity {
 *     private String concertName;
 *     private String venue;
 *
 *     @Column(name = "ticket_price")  // developer tùy ý đặt tên cột
 *     private long price;
 * }
 * }</pre>
 *
 * <p>Nếu developer muốn tên cột khác với tên field mặc định, dùng
 * {@code @AttributeOverride}:
 * <pre>{@code
 * @Entity
 * @AttributeOverride(name = "available", column = @Column(name = "remaining_qty"))
 * public class HotelRoom extends AbstractInventoryEntity { ... }
 * }</pre>
 */
@MappedSuperclass
@Getter
@Setter
@NoArgsConstructor
public abstract class AbstractInventoryEntity {

    /**
     * Định danh duy nhất của resource — framework dùng làm key.
     * Developer tự chọn giá trị (vd: "show-2026-06-15", "room-101").
     */
    @Id
    @Column(name = "resource_id", nullable = false, length = 128)
    private String resourceId;

    /** Tổng số lượng ban đầu — không thay đổi trừ khi restock. */
    @Column(name = "total_quantity", nullable = false)
    private long total;

    /**
     * Số lượng còn có thể đặt.
     * <b>P1/P2:</b> source of truth, update trong transaction.
     * <b>P3:</b> sync async từ Redis, có thể lag &lt; 1s.
     */
    @Column(name = "available_quantity", nullable = false)
    private long available;

    /**
     * Ngưỡng cảnh báo low stock.
     * Framework tự publish event khi available &le; threshold.
     * Default: 0 (disabled).
     */
    @Column(name = "low_stock_threshold", nullable = false)
    private long lowStockThreshold;

    /**
     * Optimistic lock version — P2 dùng, Hibernate tự tăng mỗi khi update.
     * P1/P3 không dùng nhưng giữ lại cho schema thống nhất.
     */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** Thời điểm update gần nhất — framework tự set. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AbstractInventoryEntity(String resourceId, long total) {
        this(resourceId, total, 0);
    }

    protected AbstractInventoryEntity(String resourceId, long total, long lowStockThreshold) {
        this.resourceId = resourceId;
        this.total = total;
        this.available = total;
        this.lowStockThreshold = lowStockThreshold;
        this.version = 0;
        this.updatedAt = Instant.now();
    }

    public boolean isLowStock() {
        return lowStockThreshold > 0 && available <= lowStockThreshold;
    }

    public boolean isDepleted() {
        return available <= 0;
    }
}
