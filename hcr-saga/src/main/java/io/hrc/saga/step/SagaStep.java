package io.hrc.saga.step;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.saga.context.SagaContext;

/**
 * Dai dien cho 1 buoc trong Saga flow.
 *
 * <p>Moi step co 2 hanh dong:
 * <ul>
 *   <li>{@link #execute} — thuc thi buoc nay (reserve, charge, confirm...)</li>
 *   <li>{@link #compensate} — hoan tac neu buoc sau fail (release, refund...)</li>
 * </ul>
 *
 * <p>Framework cung cap 3 step mac dinh:
 * {@link ReservationStep}, {@link PaymentStep}, {@link ConfirmationStep}.
 *
 * @param <O> kieu order cu the cua developer
 */
public interface SagaStep<O extends AbstractOrder> {

    /**
     * Thuc thi buoc nay.
     *
     * @param context chua order va ket qua cac buoc truoc
     * @return SUCCESS neu thanh cong, FAILED neu that bai
     */
    StepResult execute(SagaContext<O> context);

    /**
     * Hoan tac buoc nay — goi khi buoc SAU fail.
     * Compensate KHONG DUOC throw exception — log error va de Reconciliation xu ly.
     *
     * @param context chua order va ket qua cac buoc truoc
     */
    void compensate(SagaContext<O> context);

    /** Ten buoc — dung trong logging va metrics. */
    String getStepName();

    /** Buoc nay co the retry khong? */
    boolean isRetryable();
}
