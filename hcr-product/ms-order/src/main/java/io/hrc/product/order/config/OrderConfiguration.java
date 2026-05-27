package io.hrc.product.order.config;

import io.hrc.autoconfigure.HcrProperties;
import io.hrc.eventbus.EventBus;
import io.hrc.inventory.factory.InventoryStrategyFactory;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.observability.FrameworkMetrics;
import io.hrc.payment.gateway.PaymentGateway;
import io.hrc.payment.model.PaymentResult;
import io.hrc.product.order.domain.ConcertTicket;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.product.order.payment.RemotePaymentGateway;
import io.hrc.product.order.saga.TicketBookingOrchestrator;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.inventory.InventoryReconciler;
import io.hrc.saga.payment.AutoChargeInitiation;
import io.hrc.saga.payment.PaymentInitiationStrategy;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * ms-order chay reserve/release qua RedisAtomicStrategy share Redis voi ms-inventory (Path A).
 *
 * <p>InventoryStrategy chi can Redis ops (reserve/release/getAvailable) — entityManager
 * + ConcertTicket.class chi duoc dung neu goi {@code initialize()}, ma ms-order khong goi.
 *
 * <p>InventoryReconciler duoc tao manual de inject vao TicketReconciliationService — no query DB
 * inventory_db nhung vi ms-order khong own bang do nen reconcile chi chay khi
 * {@code getResourceIdsToReconcile()} non-empty (TicketReconciliationService tra empty).
 */
@Configuration
@Slf4j
public class OrderConfiguration {

    @Bean
    public InventoryStrategy inventoryStrategy(EntityManager entityManager,
                                                TransactionTemplate transactionTemplate,
                                                ApplicationEventPublisher eventPublisher,
                                                FrameworkMetrics frameworkMetrics,
                                                HcrProperties hcrProperties,
                                                EventBus eventBus,
                                                StringRedisTemplate redisTemplate) {
        InventoryStrategyFactory factory = new InventoryStrategyFactory(
                entityManager,
                ConcertTicket.class,
                transactionTemplate,
                eventPublisher,
                frameworkMetrics,
                redisTemplate,
                eventBus);
        return factory.create(hcrProperties.getInventory().getStrategy());
    }

    @Bean
    public InventoryReconciler inventoryReconciler(InventoryStrategy inventoryStrategy,
                                                    StringRedisTemplate redisTemplate,
                                                    EntityManager entityManager,
                                                    EventBus eventBus,
                                                    ReconciliationMetrics reconciliationMetrics) {
        return new InventoryReconciler(
                inventoryStrategy,
                redisTemplate,
                entityManager,
                ConcertTicket.class,
                eventBus,
                reconciliationMetrics,
                0L);
    }

    /**
     * PaymentGateway cua ms-order — proxy HTTP sang ms-payment. ms-payment la service
     * duy nhat tich hop cong thanh toan; ca P1/P2 (sync charge) lan P3 (AutoCharge async +
     * reconciliation queryStatus) deu di qua bean nay.
     *
     * <p>Khai bao @Bean o day lam auto-config bo qua MockPaymentGateway mac dinh
     * ({@code @ConditionalOnMissingBean}). ms-order khong con gateway local.
     */
    @Bean
    public PaymentGateway paymentGateway(
            @Value("${hcr.product.ms-payment.base-url:http://localhost:8083}") String msPaymentBaseUrl) {
        return new RemotePaymentGateway(msPaymentBaseUrl);
    }

    /**
     * Pool background cho AutoChargeInitiation. Tach pool rieng (KHONG dung HTTP worker)
     * de gateway cham khong an worker cua POST /orders (bulkhead pattern).
     *
     * <p>Fixed pool bounded — peak traffic vuot kha nang xu ly → task vao queue,
     * backpressure tu nhien thay vi tao unlimited thread → OOM.
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService autoChargeExecutor(
            @Value("${hcr.product.payment.auto-charge.threads:20}") int threads) {
        AtomicInteger counter = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "auto-charge-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Strategy quyet dinh CACH thanh toan duoc kich hoat sau khi reserve thanh cong.
     *
     * <p>Default {@link AutoChargeInitiation} (card-on-file): goi
     * {@code paymentGateway.charge()} async tren {@link #autoChargeExecutor}, khi xong
     * goi thang {@code orchestrator.handlePaymentResult} — KHONG qua Kafka.
     *
     * <p>{@code @Lazy} TRANH circular: orchestrator can strategy trong constructor,
     * strategy can orchestrator de wire outcome handler. Spring tao proxy luc inject,
     * resolve thanh real bean luc goi method.
     */
    @Bean
    public PaymentInitiationStrategy<TicketOrder> paymentInitiationStrategy(
            PaymentGateway paymentGateway,
            ExecutorService autoChargeExecutor,
            @Lazy TicketBookingOrchestrator orchestrator) {
        // Policy: chi SUCCESS/FAILED dispatch sang orchestrator de confirm/cancel.
        // TIMEOUT/UNKNOWN giu order o RESERVED -- reconciliation se hoi lai gateway
        // o cycle sau de chot, tranh huy oan order ma cong thanh toan thuc ra OK.
        // Cung policy voi PaymentResultListener (xu ly external payment events).
        BiConsumer<String, PaymentResult> outcomeHandler = (orderId, result) -> {
            if (result.isSuccess() || result.isFailed()) {
                orchestrator.handlePaymentResult(orderId, result);
            } else {
                log.info("[AutoCharge] Payment {} — giu order RESERVED cho reconciliation: orderId={}",
                        result.getStatus(), orderId);
            }
        };
        return new AutoChargeInitiation<>(paymentGateway, autoChargeExecutor, outcomeHandler);
    }
}
