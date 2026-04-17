package io.hrc.sample.domain;

import io.hrc.core.domain.OrderRequest;
import io.hrc.core.exception.ValidationException;
import io.hrc.core.result.ValidationResult;

/**
 * Request đặt vé concert từ client.
 *
 * <p>Extends {@link OrderRequest} để framework validate và tạo TicketOrder từ đây.
 */
public class TicketRequest extends OrderRequest {

    private final String buyerEmail;

    public TicketRequest(String resourceId, String requesterId, int quantity,
                          String idempotencyKey, String buyerEmail) {
        super(resourceId, requesterId, quantity, idempotencyKey);
        this.buyerEmail = buyerEmail;
    }

    public String getBuyerEmail() {
        return buyerEmail;
    }

    @Override
    public void validateRequest() {
        ValidationResult result = ValidationResult.ok();

        if (getQuantity() > 4) {
            result = result.merge(ValidationResult.fail("quantity",
                    "Tối đa 4 vé mỗi lần đặt, yêu cầu: " + getQuantity()));
        }

        if (buyerEmail == null || !buyerEmail.contains("@")) {
            result = result.merge(ValidationResult.fail("buyerEmail",
                    "Email không hợp lệ: " + buyerEmail));
        }

        result.throwIfInvalid();
    }

    public static TicketRequest of(String resourceId, int quantity) {
        return new TicketRequest(resourceId, "user-test", quantity,
                java.util.UUID.randomUUID().toString(), "test@example.com");
    }
}
