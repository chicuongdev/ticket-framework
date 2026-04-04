package io.hrc.core.result;

import lombok.Getter;

import java.time.Instant;

/**
 * Kết quả trả về từ mọi thao tác reserve/release inventory.
 *
 * <p>Thiết kế theo <b>Result Object pattern</b> — không throw exception mà trả về object
 * chứa trạng thái và thông tin chi tiết. Exception chỉ được dùng cho lỗi hệ thống
 * bất ngờ (vd: mất kết nối DB/Redis).
 *
 * <p>Dùng factory methods thay vì constructor:
 * <pre>{@code
 * ReservationResult result = inventoryStrategy.reserve(resourceId, quantity);
 * if (result.isSuccess()) {
 *     // tiếp tục sang payment
 * } else if (result.isInsufficient()) {
 *     // trả về lỗi hết hàng cho client
 * }
 * }</pre>
 */
@Getter
public class ReservationResult {

    /** Ba trạng thái có thể xảy ra khi reserve. */
    public enum Status {
        /** Reserve thành công — inventory đã được trừ. */
        SUCCESS,
        /** Không đủ tồn kho để đáp ứng quantity yêu cầu. */
        INSUFFICIENT,
        /** Lỗi hệ thống (DB/Redis lỗi, timeout...). */
        ERROR
    }

    private final Status status;

    /** ID tài nguyên vừa được thao tác. */
    private final String resourceId;

    /** Số lượng được yêu cầu trong request. */
    private final int requestedQuantity;

    /**
     * Số lượng tồn kho còn lại sau khi reserve thành công.
     * Null nếu status != SUCCESS.
     */
    private final Long remainingAfter;

    /**
     * Thời điểm reserve thành công.
     * Null nếu status != SUCCESS.
     */
    private final Instant reservedAt;

    /** Thông báo lỗi chi tiết. Null nếu status == SUCCESS. */
    private final String errorMessage;

    private ReservationResult(Status status, String resourceId, int requestedQuantity,
                              Long remainingAfter, Instant reservedAt, String errorMessage) {
        this.status = status;
        this.resourceId = resourceId;
        this.requestedQuantity = requestedQuantity;
        this.remainingAfter = remainingAfter;
        this.reservedAt = reservedAt;
        this.errorMessage = errorMessage;
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    public static ReservationResult success(String resourceId, int requestedQuantity, long remainingAfter) {
        return new ReservationResult(
            Status.SUCCESS, resourceId, requestedQuantity,
            remainingAfter, Instant.now(), null
        );
    }

    public static ReservationResult insufficient(String resourceId, int requestedQuantity) {
        return new ReservationResult(
            Status.INSUFFICIENT, resourceId, requestedQuantity,
            null, null,
            String.format("Không đủ tồn kho. Yêu cầu: %d", requestedQuantity)
        );
    }

    public static ReservationResult error(String resourceId, int requestedQuantity, String errorMessage) {
        return new ReservationResult(
            Status.ERROR, resourceId, requestedQuantity,
            null, null, errorMessage
        );
    }

    // -------------------------------------------------------------------------
    // Convenience methods
    // -------------------------------------------------------------------------

    public boolean isSuccess() {
        return this.status == Status.SUCCESS;
    }

    public boolean isInsufficient() {
        return this.status == Status.INSUFFICIENT;
    }

    public boolean isError() {
        return this.status == Status.ERROR;
    }
}
