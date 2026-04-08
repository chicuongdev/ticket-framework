package io.hrc.saga.context;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.result.ReservationResult;
import io.hrc.payment.model.PaymentResult;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mang state xuyen suot 1 Saga execution — truyen giua cac {@link io.hrc.saga.step.SagaStep}.
 *
 * <p>Tranh query DB lap lai: moi buoc doc/ghi thong tin qua context thay vi
 * load lai order tu DB.
 *
 * <p>Trong async mode, SagaContext duoc serialize thanh JSON va luu vao
 * {@link io.hrc.saga.repository.SagaStateRepository} de survive crash.
 *
 * @param <O> kieu order cu the cua developer
 */
@Getter
@Setter
public class SagaContext<O extends AbstractOrder> {

    /** Order dang duoc xu ly. */
    private O order;

    /** Ket qua reserve inventory (set boi ReservationStep). */
    private ReservationResult reservationResult;

    /** Ket qua thanh toan (set boi PaymentStep). */
    private PaymentResult paymentResult;

    /** Danh sach ten cac step da hoan thanh — dung de compensate theo thu tu nguoc. */
    private final List<String> completedSteps = new ArrayList<>();

    /** Danh sach ten cac step da fail. */
    private final List<String> failedSteps = new ArrayList<>();

    /** Developer them metadata tuy y (vd: seatNumbers, promoCode...). */
    private final Map<String, Object> metadata = new HashMap<>();

    /** Correlation ID cho distributed tracing — propagate vao moi event. */
    private String correlationId;

    public SagaContext(O order, String correlationId) {
        this.order = order;
        this.correlationId = correlationId;
    }

    public void markStepCompleted(String stepName) {
        completedSteps.add(stepName);
    }

    public void markStepFailed(String stepName) {
        failedSteps.add(stepName);
    }

    public boolean hasCompletedStep(String stepName) {
        return completedSteps.contains(stepName);
    }

    public void addMetadata(String key, Object value) {
        metadata.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        return (T) metadata.get(key);
    }
}
