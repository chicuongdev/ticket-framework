package io.hrc.autoconfigure.actuator;

import io.hrc.autoconfigure.HcrProperties;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.EventBusCapabilities;
import io.hrc.inventory.strategy.InventoryStrategy;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator endpoint tại {@code GET /actuator/hcr}.
 *
 * <p>Expose thông tin framework đang active để debug config tại runtime.
 *
 * <pre>
 * GET /actuator/hcr
 * {
 *   "framework": { "version": "1.0.0-SNAPSHOT", "checkedAt": "..." },
 *   "inventory": { "strategy": "redis-atomic", "consistency": "EVENTUAL", ... },
 *   "saga": { "mode": "async" },
 *   "eventBus": { "type": "kafka", "capabilities": { ... } },
 *   "gateway": { "rateLimiterEnabled": true, "permitsPerSecond": 100 },
 *   "reconciliation": { "scheduleDelayMs": 300000 }
 * }
 * </pre>
 *
 * <p>Disable qua: {@code management.endpoint.hcr.enabled=false}.
 */
@Endpoint(id = "hcr")
public class HcrActuatorEndpoint {

    private static final String VERSION = "1.0.0-SNAPSHOT";

    private final HcrProperties properties;
    private final InventoryStrategy inventoryStrategy;
    private final EventBus eventBus;

    public HcrActuatorEndpoint(HcrProperties properties,
                                InventoryStrategy inventoryStrategy,
                                EventBus eventBus) {
        this.properties = properties;
        this.inventoryStrategy = inventoryStrategy;
        this.eventBus = eventBus;
    }

    @ReadOperation
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Thông tin framework
        Map<String, Object> framework = new LinkedHashMap<>();
        framework.put("version", VERSION);
        framework.put("checkedAt", Instant.now().toString());
        result.put("framework", framework);

        // Inventory (tài nguyên)
        Map<String, Object> inventory = new LinkedHashMap<>();
        inventory.put("configuredStrategy", properties.getInventory().getStrategy());
        if (inventoryStrategy != null) {
            inventory.put("activeStrategy", inventoryStrategy.getStrategyName());
            inventory.put("consistencyLevel", inventoryStrategy.getConsistencyLevel().name());
        }
        inventory.put("circuitBreakerEnabled", properties.getInventory().getCircuitBreaker().isEnabled());
        inventory.put("persistenceMode", properties.getInventory().getPersistence().getMode());
        result.put("inventory", inventory);

        // Saga orchestration
        Map<String, Object> saga = new LinkedHashMap<>();
        saga.put("mode", properties.getSaga().getMode());
        saga.put("reservationTimeoutMinutes", properties.getSaga().getReservationTimeoutMinutes());
        saga.put("allowPartialFulfillment", properties.getSaga().isAllowPartialFulfillment());
        result.put("saga", saga);

        // EventBus (bus sự kiện)
        Map<String, Object> eventBusInfo = new LinkedHashMap<>();
        eventBusInfo.put("type", properties.getEventBus().getType());
        if (eventBus != null) {
            EventBusCapabilities caps = eventBus.getCapabilities();
            Map<String, Boolean> capMap = new LinkedHashMap<>();
            capMap.put("supportsOrdering", caps.isSupportsOrdering());
            capMap.put("supportsReplay", caps.isSupportsReplay());
            capMap.put("supportsExactlyOnce", caps.isSupportsExactlyOnce());
            capMap.put("isSynchronous", caps.isSynchronous());
            capMap.put("supportsDLQ", caps.isSupportsDLQ());
            eventBusInfo.put("capabilities", capMap);
        }
        result.put("eventBus", eventBusInfo);

        // Gateway (cổng vào)
        Map<String, Object> gateway = new LinkedHashMap<>();
        gateway.put("rateLimiterEnabled", properties.getGateway().getRateLimiter().isEnabled());
        gateway.put("permitsPerSecond", properties.getGateway().getRateLimiter().getPermitsPerSecond());
        gateway.put("burstCapacity", properties.getGateway().getRateLimiter().getBurstCapacity());
        gateway.put("idempotencyTtlSeconds", properties.getGateway().getIdempotencyTtlSeconds());
        result.put("gateway", gateway);

        // Payment (thanh toán)
        Map<String, Object> payment = new LinkedHashMap<>();
        payment.put("timeoutMs", properties.getPayment().getTimeoutMs());
        payment.put("maxRetries", properties.getPayment().getMaxRetries());
        payment.put("pollingIntervalMs", properties.getPayment().getPollingIntervalMs());
        result.put("payment", payment);

        // Reconciliation (đối soát)
        Map<String, Object> reconciliation = new LinkedHashMap<>();
        reconciliation.put("scheduleDelayMs", properties.getReconciliation().getScheduleDelayMs());
        reconciliation.put("timeoutMinutes", properties.getReconciliation().getTimeoutMinutes());
        reconciliation.put("mismatchThreshold", properties.getReconciliation().getInventoryMismatchThreshold());
        result.put("reconciliation", reconciliation);

        return result;
    }
}
