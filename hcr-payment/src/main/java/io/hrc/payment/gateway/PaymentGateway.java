package io.hrc.payment.gateway;

import io.hrc.payment.model.*;

/**
 * Contract với payment gateway bên thứ 3 (VNPay, Stripe, MoMo, ZaloPay...).
 *
 * <p>Developer KHÔNG implement trực tiếp interface này. Thay vào đó, extend
 * {@link AbstractPaymentGateway} — framework đã xử lý sẵn idempotency,
 * timeout detection, retry logic, metrics. Developer chỉ cần implement
 * {@code doCharge()}, {@code doQuery()}, {@code doRefund()}.
 *
 * <p>3 nhóm operations:
 * <ul>
 *   <li><b>Core</b> — charge, queryStatus, refund, partialRefund</li>
 *   <li><b>Pre-Authorization</b> — preAuthorize, capture, voidAuthorization
 *       (giữ tiền trước, charge sau — dùng cho khách sạn, car rental...)</li>
 *   <li><b>Health</b> — isAvailable, getHealth, getGatewayName</li>
 * </ul>
 *
 * <p><b>Tình huống A:</b> gateway crash → không có response → {@code queryStatus()} để xác minh.
 * <br><b>Tình huống B:</b> gateway thành công nhưng response bị mất → {@code queryStatus()} tìm lại.
 */
public interface PaymentGateway {

    // =========================================================================
    // CORE OPERATIONS
    // =========================================================================

    /**
     * Thực hiện thanh toán.
     *
     * @param request chứa transactionId (idempotency key), amount, currency
     * @return SUCCESS / FAILED / TIMEOUT / UNKNOWN
     */
    PaymentResult charge(PaymentRequest request);

    /**
     * Query kết quả giao dịch từ gateway.
     * Giải quyết Tình huống A (gateway crash) và B (response mất).
     *
     * @param transactionId ID giao dịch cần query
     * @return kết quả thực tế từ gateway
     */
    PaymentResult queryStatus(String transactionId);

    /**
     * Hoàn tiền toàn bộ hoặc một phần.
     * Dùng khi Saga compensate: cancel order → refund.
     */
    RefundResult refund(RefundRequest request);

    /**
     * Hoàn tiền một phần cho giao dịch đã charge.
     *
     * @param transactionId ID giao dịch gốc
     * @param amount        số tiền cần refund (< amount gốc)
     */
    RefundResult partialRefund(String transactionId, long amount);

    // =========================================================================
    // PRE-AUTHORIZATION (giữ tiền trước, charge sau)
    // =========================================================================

    /**
     * Giữ tiền trong tài khoản khách hàng — chưa trừ thực tế.
     * Dùng cho use case: đặt phòng khách sạn, thuê xe.
     */
    AuthorizationResult preAuthorize(PaymentRequest request);

    /**
     * Thực thu tiền đã giữ (capture sau pre-authorize).
     *
     * @param authorizationId ID từ {@link AuthorizationResult#getAuthorizationId()}
     */
    PaymentResult capture(String authorizationId);

    /**
     * Hủy việc giữ tiền — trả lại cho khách.
     *
     * @param authorizationId ID từ {@link AuthorizationResult#getAuthorizationId()}
     */
    void voidAuthorization(String authorizationId);

    // =========================================================================
    // HEALTH
    // =========================================================================

    /**
     * Gateway có đang hoạt động không? Quick check, không nên tốn nhiều thời gian.
     */
    boolean isAvailable();

    /**
     * Thông tin health chi tiết: success rate, latency, connection count.
     */
    GatewayHealth getHealth();

    /**
     * Tên gateway — dùng trong metrics và logging.
     * Ví dụ: "vnpay", "stripe", "momo", "mock".
     */
    String getGatewayName();
}
