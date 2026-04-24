package io.hrc.product.order.reconciliation;

import io.hrc.core.domain.OrderAccessor;
import io.hrc.core.enums.FailureReason;
import io.hrc.core.enums.OrderStatus;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentResult;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.product.order.repository.TicketOrderRepository;
import io.hrc.reconciliation.AbstractReconciliationService;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.inventory.InventoryReconciler;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reconciliation cho ms-order — quét DB tìm 5 case inconsistency, fix bằng cách
 * cancel order + release Redis inventory.
 *
 * <p>Case 3 (INVENTORY_MISMATCH) chạy trên ms-inventory's database — ms-order chỉ
 * alert (không có quyền sửa inventory_db). Case 4 (UNPERSISTED_RESERVATION) tương tự.
 */
@Service
@Slf4j
public class TicketReconciliationService extends AbstractReconciliationService<TicketOrder> {

    private final TicketOrderRepository orderRepository;
    private final InventoryStrategy inventoryStrategy;

    public TicketReconciliationService(PaymentGateway paymentGateway,
                                        InventoryReconciler inventoryReconciler,
                                        EventBus eventBus,
                                        RedissonClient redissonClient,
                                        ReconciliationMetrics metrics,
                                        TicketOrderRepository orderRepository,
                                        InventoryStrategy inventoryStrategy) {
        super(paymentGateway, inventoryReconciler, eventBus, redissonClient, metrics);
        this.orderRepository = orderRepository;
        this.inventoryStrategy = inventoryStrategy;
    }

    @Override
    protected List<TicketOrder> findStalePendingOrders(int timeoutMinutes) {
        Instant before = Instant.now().minus(timeoutMinutes, ChronoUnit.MINUTES);
        // RESERVED ngầm 'pending payment' trong async flow — sync flow's PENDING không tồn tại lâu
        List<TicketOrder> reserved = orderRepository.findByStatusAndUpdatedAtBefore(
                OrderStatus.RESERVED, before);
        List<TicketOrder> pending = orderRepository.findByStatusAndUpdatedAtBefore(
                OrderStatus.PENDING, before);
        List<TicketOrder> all = new ArrayList<>(reserved.size() + pending.size());
        all.addAll(reserved);
        all.addAll(pending);
        return all;
    }

    @Override
    protected String getPaymentTransactionId(TicketOrder order) {
        return order.getOrderId();
    }

    @Override
    protected void handleTimeout(TicketOrder order) {
        // Release Redis (Path A: ms-order share Redis với ms-inventory)
        try {
            inventoryStrategy.release(order.getResourceId(),
                    "reconcile-timeout-" + order.getOrderId(),
                    order.getQuantity());
        } catch (Exception e) {
            log.warn("[Reconcile] Release failed (will retry next cycle): orderId={}, error={}",
                    order.getOrderId(), e.getMessage());
        }
        OrderAccessor.transitionTo(order, OrderStatus.CANCELLED);
        OrderAccessor.markFailedWith(order, FailureReason.PAYMENT_TIMEOUT);
        orderRepository.save(order);
    }

    @Override
    protected void handleLatePaymentSuccess(TicketOrder order, PaymentResult result) {
        // Tiền đã trừ — confirm order, không release inventory
        OrderAccessor.transitionTo(order, OrderStatus.CONFIRMED);
        orderRepository.save(order);
        log.warn("[Reconcile] LATE_PAYMENT_SUCCESS resolved by confirming: orderId={}, gatewayTx={}",
                order.getOrderId(), result.getGatewayTransactionId());
    }

    @Override
    protected void handleInventoryMismatch(String resourceId, long redisAvailable, long dbAvailable) {
        // ms-order không own inventory_db — chỉ log alert. ms-inventory tự reconcile cycle riêng.
        log.warn("[Reconcile] INVENTORY_MISMATCH resourceId={}, redis={}, db={} — ops alert only",
                resourceId, redisAvailable, dbAvailable);
    }

    @Override
    protected List<TicketOrder> findUnpersistedReservations() {
        // ms-order không quản lý inventory_db → bỏ qua case này
        return List.of();
    }

    @Override
    protected void handleUnpersistedReservation(TicketOrder order) {
        // no-op
    }

    @Override
    protected List<List<TicketOrder>> findDuplicateOrders() {
        List<String> dupKeys = orderRepository.findDuplicateIdempotencyKeys();
        if (dupKeys.isEmpty()) return List.of();
        return dupKeys.stream()
                .map(orderRepository::findAllByIdempotencyKey)
                .filter(g -> g.size() > 1)
                .collect(Collectors.toList());
    }

    @Override
    protected void handleDuplicateOrders(List<TicketOrder> duplicates) {
        // Giữ order CONFIRMED > RESERVED > PENDING > others; cancel phần còn lại
        Map<OrderStatus, Integer> rank = Map.of(
                OrderStatus.CONFIRMED, 0,
                OrderStatus.RESERVED, 1,
                OrderStatus.PENDING, 2);
        TicketOrder keeper = duplicates.stream()
                .min(Comparator.comparingInt(o -> rank.getOrDefault(o.getStatus(), 99)))
                .orElseThrow();

        for (TicketOrder o : duplicates) {
            if (o.getOrderId().equals(keeper.getOrderId())) continue;
            if (o.isTerminal()) continue;
            try {
                inventoryStrategy.release(o.getResourceId(),
                        "duplicate-cancel-" + o.getOrderId(),
                        o.getQuantity());
            } catch (Exception e) {
                log.warn("[Reconcile] Release failed for dup orderId={}: {}",
                        o.getOrderId(), e.getMessage());
            }
            OrderAccessor.transitionTo(o, OrderStatus.CANCELLED);
            OrderAccessor.markFailedWith(o, FailureReason.DUPLICATE_REQUEST);
            orderRepository.save(o);
        }
        log.warn("[Reconcile] Duplicate group resolved: kept={}, cancelled={}",
                keeper.getOrderId(), duplicates.size() - 1);
    }

    @Override
    protected List<String> getResourceIdsToReconcile() {
        // ms-order không reconcile inventory mismatch (delegated to ms-inventory)
        return List.of();
    }
}
