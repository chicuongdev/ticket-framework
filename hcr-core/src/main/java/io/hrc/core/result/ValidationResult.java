package io.hrc.core.result;

import io.hrc.core.exception.ValidationException;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Kết quả validation — chứa danh sách lỗi chi tiết nếu có.
 * Được dùng trong Gateway để validate request trước khi xử lý.
 *
 * <p>Dùng factory methods và chain để gom nhiều lỗi lại:
 * <pre>{@code
 * ValidationResult result = ValidationResult.ok()
 *     .merge(validateQuantity(request))
 *     .merge(validateResource(request));
 *
 * result.throwIfInvalid(); // throw ValidationException nếu có lỗi
 * }</pre>
 */
@Getter
public class ValidationResult {

    /**
     * Chi tiết một lỗi validation đơn lẻ.
     */
    @Getter
    public static class ValidationError {

        /** Tên field bị lỗi (vd: "quantity", "resourceId"). */
        private final String field;

        /** Thông báo lỗi dễ đọc. */
        private final String message;

        /** Giá trị bị reject (để client biết giá trị nào không hợp lệ). */
        private final Object rejectedValue;

        public ValidationError(String field, String message, Object rejectedValue) {
            this.field = field;
            this.message = message;
            this.rejectedValue = rejectedValue;
        }

        public ValidationError(String field, String message) {
            this(field, message, null);
        }
    }

    private final boolean valid;
    private final List<ValidationError> errors;

    private ValidationResult(boolean valid, List<ValidationError> errors) {
        this.valid = valid;
        this.errors = Collections.unmodifiableList(errors);
    }

    // -------------------------------------------------------------------------
    // Factory methods
    // -------------------------------------------------------------------------

    /** Tạo result hợp lệ — không có lỗi. */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    /**
     * Tạo result với 1 lỗi.
     *
     * @param field         tên field bị lỗi
     * @param message       thông báo lỗi
     * @param rejectedValue giá trị bị reject
     */
    public static ValidationResult fail(String field, String message, Object rejectedValue) {
        List<ValidationError> errors = new ArrayList<>();
        errors.add(new ValidationError(field, message, rejectedValue));
        return new ValidationResult(false, errors);
    }

    /** Tạo result với 1 lỗi (không có rejectedValue). */
    public static ValidationResult fail(String field, String message) {
        return fail(field, message, null);
    }

    // -------------------------------------------------------------------------
    // Operations
    // -------------------------------------------------------------------------

    /**
     * Gộp 2 ValidationResult lại.
     * Dùng khi validate nhiều tầng (framework + business rule).
     *
     * @param other ValidationResult cần gộp
     * @return ValidationResult mới chứa errors từ cả hai
     */
    public ValidationResult merge(ValidationResult other) {
        if (other == null || other.isValid()) {
            return this;
        }
        List<ValidationError> merged = new ArrayList<>(this.errors);
        merged.addAll(other.errors);
        return new ValidationResult(merged.isEmpty(), merged);
    }

    /**
     * Throw {@link ValidationException} nếu result không hợp lệ.
     * Convenience method để dùng trong pipeline thay vì if-else.
     */
    public void throwIfInvalid() {
        if (!valid) {
            throw new ValidationException(this);
        }
    }

    /** @return true nếu không có lỗi nào. */
    public boolean isValid() {
        return valid;
    }
}
