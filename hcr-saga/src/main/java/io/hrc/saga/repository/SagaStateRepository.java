package io.hrc.saga.repository;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.enums.OrderStatus;
import io.hrc.saga.context.SagaContext;

import java.util.List;
import java.util.Optional;

/**
 * Luu trang thai Saga de resume neu crash giua chung.
 *
 * <p><b>MANDATORY cho async mode.</b> Framework throw exception khi startup
 * neu {@code AsynchronousSagaOrchestrator} duoc dung ma khong co bean nay:
 * <pre>
 * HcrFrameworkException: AsynchronousSaga requires a SagaStateRepository bean.
 * </pre>
 *
 * <p><b>Optional cho sync mode.</b> Crash trong sync → client retry → tao Saga moi → OK.
 *
 * <p><b>Goi y implement don gian nhat:</b> Them column {@code saga_state} (JSONB)
 * vao bang orders, serialize SagaContext thanh JSON. Hoac dung Redis
 * de giu DB ngoai critical path (phu hop P3 async).
 *
 * @param <O> kieu order cu the cua developer
 */
public interface SagaStateRepository<O extends AbstractOrder> {

    /** Luu hoac update saga state. */
    void save(SagaContext<O> context);

    /** Tim saga state theo orderId. */
    Optional<SagaContext<O>> findByOrderId(String orderId);

    /** Xoa saga state sau khi saga hoan thanh (CONFIRMED hoac CANCELLED). */
    void delete(String orderId);

    /** Tim tat ca saga dang o trang thai nhat dinh — dung cho Reconciliation. */
    List<SagaContext<O>> findByStatus(OrderStatus status);
}
