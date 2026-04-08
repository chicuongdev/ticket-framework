package io.hrc.saga.step;

import io.hrc.core.enums.FailureReason;
import lombok.Getter;

/**
 * Ket qua thuc thi cua mot {@link SagaStep}.
 *
 * <p>3 trang thai:
 * <ul>
 *   <li>{@link StepStatus#SUCCESS} — buoc thanh cong, tiep tuc buoc tiep theo.</li>
 *   <li>{@link StepStatus#FAILED} — buoc that bai, bat dau compensation.</li>
 *   <li>{@link StepStatus#RETRY} — buoc co the thu lai (chua implement retry logic).</li>
 * </ul>
 */
@Getter
public class StepResult {

    private final StepStatus status;
    private final FailureReason failureReason;
    private final String errorMessage;

    public enum StepStatus {
        SUCCESS, FAILED, RETRY
    }

    private StepResult(StepStatus status, FailureReason failureReason, String errorMessage) {
        this.status = status;
        this.failureReason = failureReason;
        this.errorMessage = errorMessage;
    }

    public static StepResult success() {
        return new StepResult(StepStatus.SUCCESS, null, null);
    }

    public static StepResult failed(FailureReason reason, String message) {
        return new StepResult(StepStatus.FAILED, reason, message);
    }

    public static StepResult retry(String reason) {
        return new StepResult(StepStatus.RETRY, null, reason);
    }

    public boolean isSuccess() {
        return status == StepStatus.SUCCESS;
    }

    public boolean isFailed() {
        return status == StepStatus.FAILED;
    }

    public boolean shouldRetry() {
        return status == StepStatus.RETRY;
    }
}
