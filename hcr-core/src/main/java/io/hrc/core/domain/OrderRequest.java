package io.hrc.core.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Input vào Saga Orchestrator — đại diện cho yêu cầu chưa được xử lý từ client.
 * Developer PHẢI extend class này để thêm field nghiệp vụ riêng.
 *
 * <p><b>Khác với AbstractOrder:</b> OrderRequest là dữ liệu thô từ HTTP request,
 * chưa được lưu vào DB. AbstractOrder là entity đã được tạo và persist.
 *
 * <pre>{@code
 * public class BookTicketRequest extends OrderRequest {
 *     private String seatCategory;
 *     private String promoCode;
 *
 *     @Override
 *     public void validateRequest() {
 *         if (getQuantity() > 4) {
 *             throw new ValidationException("Tối đa 4 vé mỗi lần đặt");
 *         }
 *     }
 * }
 * }</pre>
 */
@Getter
@NoArgsConstructor
public abstract class OrderRequest {

    /** ID tài nguyên muốn đặt. */
    private String resourceId;

    /** ID người gửi yêu cầu (userId, customerId...). */
    private String requesterId;

    /** Số lượng muốn đặt. */
    private int quantity;

    /**
     * Key chống duplicate request.
     * Client phải sinh key này và gửi cùng với request.
     * Framework reject nếu key đã được xử lý trong 24h gần nhất.
     */
    private String idempotencyKey;

    protected OrderRequest(String resourceId, String requesterId,
                           int quantity, String idempotencyKey) {
        this.resourceId = resourceId;
        this.requesterId = requesterId;
        this.quantity = quantity;
        this.idempotencyKey = idempotencyKey;
    }

    /**
     * Hook cho developer override để validate business rule riêng.
     * Framework gọi method này sau khi validate các field cơ bản.
     *
     * <p>Developer throw {@link io.hrc.core.exception.ValidationException}
     * nếu request không hợp lệ.
     */
    public void validateRequest() {
        // no-op — developer override để thêm rule
    }
}
