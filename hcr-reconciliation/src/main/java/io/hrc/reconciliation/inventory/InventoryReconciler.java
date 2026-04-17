package io.hrc.reconciliation.inventory;

import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.event.reconciliation.InventoryMismatchEvent;
import io.hrc.eventbus.event.reconciliation.ReconciliationFixedEvent;
import io.hrc.inventory.entity.AbstractInventoryEntity;
import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.reconciliation.ReconciliationMetrics;
import io.hrc.reconciliation.model.InventoryDelta;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Xử lý Case 3 — Inventory Mismatch: so sánh Redis vs DB và tự động fix nếu trong ngưỡng.
 *
 * <p>Chỉ có ý nghĩa khi dùng <b>P3 (RedisAtomicStrategy)</b>. Với P1/P2, DB là source of
 * truth duy nhất nên không có mismatch — không cần dùng class này.
 *
 * <h3>Kịch bản mismatch:</h3>
 * <pre>
 *   delta > 0 (Redis > DB):
 *     Redis crash + AOF restore không đầy đủ (mất &lt; 1 giây writes).
 *     Redis được restore về giá trị cũ hơn (cao hơn thực tế).
 *     → Nguy hiểm: Redis cho phép đặt hàng dù thực tế đã hết.
 *     → Fix: set Redis về mức DB (giảm Redis xuống).
 *
 *   delta &lt; 0 (DB > Redis):
 *     Normal P3 eventual consistency lag — DB chưa được sync.
 *     Consumer đang xử lý event, lag &lt; 1 giây.
 *     → Không cần fix. Sẽ tự resolve khi consumer catch up.
 * </pre>
 *
 * <h3>Auto-fix logic:</h3>
 * <ul>
 *   <li>{@code mismatchThreshold == 0}: alert mọi mismatch nhưng không auto-fix</li>
 *   <li>{@code mismatchThreshold > 0} và {@code delta <= threshold}: tự fix Redis theo DB</li>
 *   <li>{@code delta > threshold}: chỉ alert, cần manual review</li>
 * </ul>
 *
 * <p>Redis key convention (phải khớp với {@code RedisAtomicStrategy}):
 * {@code hcr:inventory:{resourceId}}
 */
@Slf4j
public class InventoryReconciler {

    private static final String REDIS_KEY_PREFIX = "hcr:inventory:";

    private final InventoryStrategy inventoryStrategy;
    private final StringRedisTemplate redisTemplate;
    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final EventBus eventBus;
    private final ReconciliationMetrics metrics;
    private final long mismatchThreshold;

    /**
     * @param inventoryStrategy  P3 strategy — dùng để verify sau khi fix
     * @param redisTemplate      dùng để trực tiếp SET Redis khi autoFix
     * @param entityManager      dùng để đọc DB snapshot
     * @param entityClass        class entity cụ thể của developer (extends AbstractInventoryEntity)
     * @param eventBus           publish InventoryMismatchEvent và ReconciliationFixedEvent
     * @param metrics            ghi nhận metrics
     * @param mismatchThreshold  ngưỡng auto-fix. 0 = chỉ alert, không fix
     */
    public InventoryReconciler(InventoryStrategy inventoryStrategy,
                                StringRedisTemplate redisTemplate,
                                EntityManager entityManager,
                                Class<? extends AbstractInventoryEntity> entityClass,
                                EventBus eventBus,
                                ReconciliationMetrics metrics,
                                long mismatchThreshold) {
        this.inventoryStrategy = inventoryStrategy;
        this.redisTemplate = redisTemplate;
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.eventBus = eventBus;
        this.metrics = metrics;
        this.mismatchThreshold = mismatchThreshold;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Reconcile một resource: so sánh Redis vs DB, tự fix nếu trong ngưỡng.
     *
     * @param resourceId resource cần reconcile
     * @return {@code true} nếu consistent hoặc đã fix thành công
     */
    public boolean reconcile(String resourceId) {
        InventoryDelta delta = compare(resourceId);
        if (!delta.isHasMismatch()) {
            return true;
        }
        return handleMismatch(delta);
    }

    /**
     * Reconcile nhiều resource cùng lúc.
     *
     * @param resourceIds danh sách resource cần reconcile
     * @return danh sách resourceId bị mismatch (kể cả đã fix)
     */
    public List<String> reconcileAll(List<String> resourceIds) {
        List<String> mismatched = new ArrayList<>();
        for (String resourceId : resourceIds) {
            try {
                InventoryDelta delta = compare(resourceId);
                if (delta.isHasMismatch()) {
                    mismatched.add(resourceId);
                    handleMismatch(delta);
                }
            } catch (Exception e) {
                log.error("[InventoryReconciler] Error reconciling resourceId={}: {}",
                        resourceId, e.getMessage(), e);
                mismatched.add(resourceId);
            }
        }
        return mismatched;
    }

    /**
     * So sánh Redis vs DB inventory cho một resource.
     *
     * <p>Redis đọc trực tiếp qua StringRedisTemplate (không qua InventoryStrategy)
     * để tránh bất kỳ layer nào ảnh hưởng đến giá trị thô.
     *
     * @param resourceId resource cần so sánh
     * @return InventoryDelta với đủ thông tin để quyết định có cần fix không
     */
    public InventoryDelta compare(String resourceId) {
        // Đọc Redis trực tiếp
        String redisKey = REDIS_KEY_PREFIX + resourceId;
        String redisRaw = redisTemplate.opsForValue().get(redisKey);
        long redisAvailable = redisRaw != null ? Long.parseLong(redisRaw) : -1L;

        // Đọc DB
        long dbAvailable = getDbAvailable(resourceId);

        InventoryDelta delta = InventoryDelta.of(resourceId, redisAvailable, dbAvailable);

        log.debug("[InventoryReconciler] compare resourceId={}: redis={}, db={}, delta={}",
                resourceId, redisAvailable, dbAvailable, delta.getDelta());

        return delta;
    }

    /**
     * Tự động fix Redis về mức DB khi delta trong ngưỡng an toàn.
     *
     * <p>Chỉ fix khi:
     * <ul>
     *   <li>delta > 0 (Redis cao hơn DB — Redis có vấn đề)</li>
     *   <li>mismatchThreshold > 0 (developer bật auto-fix)</li>
     *   <li>delta &lt;= mismatchThreshold (trong ngưỡng an toàn)</li>
     * </ul>
     *
     * @param resourceId  resource cần fix
     * @param delta       độ lệch (redisAvailable - dbAvailable)
     * @param dbAvailable giá trị DB để set Redis về
     * @return {@code true} nếu fix thành công hoặc không cần fix
     */
    public boolean autoFix(String resourceId, long delta, long dbAvailable) {
        if (delta <= 0) {
            // DB lag bình thường — không cần fix Redis
            return true;
        }
        if (mismatchThreshold == 0 || delta > mismatchThreshold) {
            log.warn("[InventoryReconciler] Mismatch too large or auto-fix disabled. " +
                    "resourceId={}, delta={}, threshold={}. Manual review needed.",
                    resourceId, delta, mismatchThreshold);
            return false;
        }

        // Fix: set Redis về mức DB
        String redisKey = REDIS_KEY_PREFIX + resourceId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(dbAvailable));

        log.warn("[InventoryReconciler] Auto-fixed Redis for resourceId={}: {} -> {}",
                resourceId, dbAvailable + delta, dbAvailable);

        eventBus.publish(new ReconciliationFixedEvent(
                resourceId, null,
                "sync-redis-to-db",
                String.format("Redis available %d -> %d (delta was %d)",
                        dbAvailable + delta, dbAvailable, delta),
                null
        ));

        return true;
    }

    // =========================================================================
    // Internal
    // =========================================================================

    private boolean handleMismatch(InventoryDelta delta) {
        String resourceId = delta.getResourceId();

        // Publish event để các consumer biết (monitoring, alerting)
        eventBus.publish(new InventoryMismatchEvent(
                resourceId, delta.getRedisAvailable(), delta.getDbAvailable(), null));
        metrics.recordInventoryMismatch(resourceId, delta.getDelta());

        if (delta.isRedisHigherThanDb()) {
            log.warn("[InventoryReconciler] Redis > DB: resourceId={}, redis={}, db={}, delta={}",
                    resourceId, delta.getRedisAvailable(), delta.getDbAvailable(), delta.getDelta());
            return autoFix(resourceId, delta.getDelta(), delta.getDbAvailable());
        } else {
            // delta < 0: DB lag bình thường trong P3
            log.debug("[InventoryReconciler] DB > Redis (normal P3 lag): resourceId={}, redis={}, db={}",
                    resourceId, delta.getRedisAvailable(), delta.getDbAvailable());
            return true;
        }
    }

    private long getDbAvailable(String resourceId) {
        AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
        if (entity == null) {
            log.error("[InventoryReconciler] Entity not found in DB for resourceId={}", resourceId);
            return -1L;
        }
        return entity.getAvailable();
    }
}
