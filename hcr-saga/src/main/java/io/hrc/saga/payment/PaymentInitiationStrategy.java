package io.hrc.saga.payment;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.payment.model.PaymentRequest;

/**
 * Quyet dinh CACH thanh toan duoc kich hoat sau khi reserve thanh cong trong async saga.
 *
 * <p>Framework cung cap 2 default impl, dev co the tu viet:
 * <ul>
 *   <li>{@link AutoChargeInitiation} — card-on-file: ms-order tu trigger charge async
 *       (HTTP sang ms-payment), khong can user thao tac. Default cho P3.</li>
 *   <li>{@link UserConfirmInitiation} — user-initiated (VNPay/Momo redirect): no-op
 *       o phia saga; user se chu dong goi ms-payment, ket qua quay ve qua event/webhook.</li>
 * </ul>
 *
 * <p><b>Khac voi {@code PaymentRequestedEvent} truoc day:</b> abstraction nay nam o saga
 * orchestrator side, khong rang buoc dev phai dung event-driven trigger. Auto-charge co
 * the chon HTTP async, message queue, hay direct call — la quyet dinh cua impl.
 *
 * @param <O> kieu order cu the cua developer
 */
public interface PaymentInitiationStrategy<O extends AbstractOrder> {

    /**
     * Goi NGAY sau khi reserve thanh cong va saga state da persist.
     *
     * <p>Implementations chon policy:
     * <ul>
     *   <li>Auto-charge: submit charge ra background thread, publish outcome event khi xong.</li>
     *   <li>User-confirm: no-op — cho user chu dong trigger.</li>
     * </ul>
     *
     * <p>Method KHONG block — saga critical path da return HTTP 202.
     *
     * @param order         order vua reserve xong (status = RESERVED)
     * @param request       PaymentRequest da build tu order
     * @param correlationId saga correlation id (cho tracing/event)
     */
    void onResourceReserved(O order, PaymentRequest request, String correlationId);
}
