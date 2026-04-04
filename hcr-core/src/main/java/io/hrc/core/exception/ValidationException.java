package io.hrc.core.exception;

import io.hrc.core.enums.FailureReason;
import io.hrc.core.result.ValidationResult;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Ném khi request không vượt qua validation.
 * Được ném bởi {@link ValidationResult#throwIfInvalid()}.
 *
 * <p>Chứa danh sách lỗi chi tiết để client biết field nào không hợp lệ.
 */
@Getter
public class ValidationException extends FrameworkException {

    /** Danh sách lỗi chi tiết từ ValidationResult. */
    private final List<ValidationResult.ValidationError> errors;

    public ValidationException(ValidationResult result) {
        super(FailureReason.VALIDATION_FAILED, null, null, buildMessage(result.getErrors()));
        this.errors = result.getErrors();
    }

    public ValidationException(String field, String message) {
        super(FailureReason.VALIDATION_FAILED, null, null,
            String.format("Validation failed: %s - %s", field, message));
        this.errors = List.of(new ValidationResult.ValidationError(field, message));
    }

    private static String buildMessage(List<ValidationResult.ValidationError> errors) {
        return "Validation failed: " + errors.stream()
            .map(e -> e.getField() + " - " + e.getMessage())
            .collect(Collectors.joining("; "));
    }
}
