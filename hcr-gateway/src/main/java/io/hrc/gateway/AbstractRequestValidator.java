package io.hrc.gateway;

import io.hrc.core.domain.OrderRequest;
import io.hrc.core.result.ValidationResult;

/**
 * Validate request theo 2 tầng — framework lo basic validation,
 * developer lo business validation.
 *
 * <p>Đây là standalone helper class — dùng khi developer muốn tách logic
 * validation ra khỏi {@link FrameworkGateway} (ví dụ: reuse giữa nhiều gateway,
 * test riêng validation logic).
 *
 * <p><b>Tầng 1 — Framework lo (không cần developer làm gì):</b>
 * <ul>
 *   <li>resourceId không null/blank</li>
 *   <li>quantity > 0</li>
 *   <li>idempotencyKey không null/blank</li>
 *   <li>requesterId không null/blank</li>
 * </ul>
 *
 * <p><b>Tầng 2 — Developer implement {@link #validateBusinessRules}:</b>
 * <ul>
 *   <li>ticketCategory có hợp lệ không?</li>
 *   <li>user có đủ tuổi không?</li>
 *   <li>concert đã kết thúc chưa?</li>
 *   <li>user đã đặt quá maxPerUser chưa?</li>
 * </ul>
 *
 * <p><b>Flow:</b>
 * <pre>
 * validate(request):
 *   basic    = validateBasicFields(request)    // framework
 *   business = validateBusinessRules(request)  // developer
 *   return basic.merge(business)               // gộp tất cả lỗi
 * </pre>
 *
 * <p><b>Ví dụ:</b>
 * <pre>{@code
 * public class BookTicketValidator extends AbstractRequestValidator<BookTicketRequest> {
 *
 *     private final ConcertRepository concertRepo;
 *
 *     @Override
 *     protected ValidationResult validateBusinessRules(BookTicketRequest request) {
 *         ValidationResult result = ValidationResult.ok();
 *
 *         Concert concert = concertRepo.findById(request.getResourceId()).orElse(null);
 *         if (concert == null) {
 *             result = result.merge(ValidationResult.fail("resourceId", "Concert không tồn tại"));
 *         } else if (concert.hasEnded()) {
 *             result = result.merge(ValidationResult.fail("resourceId", "Concert đã kết thúc"));
 *         }
 *
 *         if (request.getQuantity() > 4) {
 *             result = result.merge(ValidationResult.fail("quantity",
 *                 "Tối đa 4 vé mỗi lần đặt", request.getQuantity()));
 *         }
 *
 *         return result;
 *     }
 * }
 * }</pre>
 *
 * @param <R> kiểu request cụ thể (extends OrderRequest)
 */
public abstract class AbstractRequestValidator<R extends OrderRequest> {

    /**
     * Validate request — gộp basic validation (framework) và business validation (developer).
     * Method này là {@code final} — developer KHÔNG override.
     *
     * @param request request cần validate
     * @return ValidationResult chứa tất cả lỗi (nếu có)
     */
    public final ValidationResult validate(R request) {
        if (request == null) {
            return ValidationResult.fail("request", "request không được null");
        }
        ValidationResult basic    = validateBasicFields(request);
        ValidationResult business = validateBusinessRules(request);
        return basic.merge(business);
    }

    /**
     * Business-specific validation — developer PHẢI implement.
     *
     * <p>Method này được gọi song song với basic validation (không fail-fast)
     * để thu thập TẤT CẢ lỗi cùng lúc thay vì từng lỗi một.
     * Developer cần defensive check nếu business logic phụ thuộc vào basic fields
     * (ví dụ: kiểm tra null trước khi query DB theo resourceId).
     *
     * <p>Developer trả về {@link ValidationResult#ok()} nếu không có lỗi,
     * hoặc dùng {@link ValidationResult#fail} để mô tả lỗi.
     * Không nên throw exception từ method này.
     *
     * @param request request cần validate (có thể có basic field lỗi)
     * @return ValidationResult với business rule errors (nếu có)
     */
    protected abstract ValidationResult validateBusinessRules(R request);

    // =========================================================================
    // Framework basic validation
    // =========================================================================

    private ValidationResult validateBasicFields(R request) {
        ValidationResult result = ValidationResult.ok();

        if (request.getResourceId() == null || request.getResourceId().isBlank()) {
            result = result.merge(ValidationResult.fail("resourceId", "không được để trống"));
        }
        if (request.getRequesterId() == null || request.getRequesterId().isBlank()) {
            result = result.merge(ValidationResult.fail("requesterId", "không được để trống"));
        }
        if (request.getQuantity() <= 0) {
            result = result.merge(ValidationResult.fail("quantity",
                    "phải > 0", request.getQuantity()));
        }
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            result = result.merge(ValidationResult.fail("idempotencyKey", "không được để trống"));
        }

        return result;
    }
}
