package io.hrc.saga.payment;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.payment.model.PaymentRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * User-initiated payment (VNPay/Momo redirect, Stripe Checkout): saga KHONG tu trigger
 * charge. User se chu dong goi ms-payment (vd: {@code POST /payments}); ms-payment xu ly
 * va publish {@link io.hrc.eventbus.event.payment.PaymentSucceededEvent} /
 * {@link io.hrc.eventbus.event.payment.PaymentFailedEvent} → saga's payment result
 * listener confirm/cancel order.
 *
 * <p><b>Note:</b> impl mac dinh la no-op. Dev override neu can:
 * <ul>
 *   <li>Goi ms-payment.createIntent() de lay paymentUrl/QR, luu vao order de tra ve user.</li>
 *   <li>Gui email/push notification thuc giuc user thanh toan.</li>
 * </ul>
 *
 * <p>Order se nam o RESERVED cho toi khi user thanh toan xong (hoac het expiresAt →
 * reconciliation huy + release inventory).
 *
 * @param <O> kieu order cu the cua developer
 */
@Slf4j
public class UserConfirmInitiation<O extends AbstractOrder> implements PaymentInitiationStrategy<O> {

    @Override
    public void onResourceReserved(O order, PaymentRequest request, String correlationId) {
        log.debug("[UserConfirm] Order RESERVED, cho user trigger payment: orderId={}",
                order.getOrderId());
    }
}
