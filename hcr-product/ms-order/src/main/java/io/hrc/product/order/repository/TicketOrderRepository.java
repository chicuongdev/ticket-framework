package io.hrc.product.order.repository;

import io.hrc.core.enums.OrderStatus;
import io.hrc.product.order.domain.TicketOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface TicketOrderRepository extends JpaRepository<TicketOrder, String> {

    Optional<TicketOrder> findByOrderId(String orderId);

    List<TicketOrder> findByStatusAndUpdatedAtBefore(OrderStatus status, Instant before);

    @Query("SELECT o.idempotencyKey FROM TicketOrder o " +
           "GROUP BY o.idempotencyKey HAVING COUNT(o.idempotencyKey) > 1")
    List<String> findDuplicateIdempotencyKeys();

    List<TicketOrder> findAllByIdempotencyKey(@Param("k") String idempotencyKey);

    /**
     * Tìm orphan: order CANCELLED/EXPIRED nhưng inventory chưa release.
     * <p>Saga compensate gọi {@code release()} fail → {@code inventoryReleasedAt} null.
     * Reconciliation Case 6 dùng query này để retry release.
     */
    @Query("SELECT o FROM TicketOrder o " +
           "WHERE o.status IN ('CANCELLED', 'EXPIRED') " +
           "  AND o.inventoryReleasedAt IS NULL " +
           "  AND o.updatedAt < :before")
    List<TicketOrder> findOrphanCancelled(@Param("before") Instant before);
}
