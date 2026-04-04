package io.hrc.inventory.initializer;

import io.hrc.inventory.entity.AbstractInventoryEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Đồng bộ dữ liệu inventory từ DB (bảng developer) lên Redis khi application startup.
 * <b>Chỉ cần thiết cho RedisAtomicStrategy (P3).</b>
 *
 * <p><b>Refactored:</b> Dùng {@link EntityManager} thay vì InventoryRecordRepository
 * → đọc trực tiếp từ bảng developer (entity extend {@link AbstractInventoryEntity}).
 */
@Slf4j
public class InventoryInitializer {

    private static final String KEY_PREFIX       = "hcr:inventory:";
    private static final String TOTAL_KEY_PREFIX = "hcr:inventory:total:";

    private final EntityManager entityManager;
    private final Class<? extends AbstractInventoryEntity> entityClass;
    private final StringRedisTemplate redisTemplate;

    public InventoryInitializer(EntityManager entityManager,
                                 Class<? extends AbstractInventoryEntity> entityClass,
                                 StringRedisTemplate redisTemplate) {
        this.entityManager = entityManager;
        this.entityClass = entityClass;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Load tất cả resource đang active (available > 0) từ DB lên Redis.
     *
     * @return số lượng resource đã được load
     */
    public int initializeAll() {
        String jpql = "SELECT e FROM " + entityClass.getSimpleName() + " e WHERE e.available > 0";
        TypedQuery<? extends AbstractInventoryEntity> query = entityManager.createQuery(jpql, entityClass);
        List<? extends AbstractInventoryEntity> records = query.getResultList();

        int count = 0;
        for (AbstractInventoryEntity record : records) {
            loadToRedis(record.getResourceId(), record.getAvailable(), record.getTotal());
            count++;
        }
        log.info("[InventoryInitializer] Loaded {} resources from DB to Redis", count);
        return count;
    }

    /**
     * Load một resource cụ thể từ DB lên Redis.
     */
    public void initialize(String resourceId) {
        AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
        if (entity == null) {
            throw new IllegalArgumentException("Resource không tồn tại trong DB: " + resourceId);
        }
        loadToRedis(resourceId, entity.getAvailable(), entity.getTotal());
        log.info("[InventoryInitializer] Loaded resource: resourceId={}, available={}",
            resourceId, entity.getAvailable());
    }

    /**
     * Force-set giá trị cụ thể trên Redis.
     */
    public void initialize(String resourceId, long available, long totalQuantity) {
        loadToRedis(resourceId, available, totalQuantity);
        log.info("[InventoryInitializer] Force-initialized: resourceId={}, available={}/{}",
            resourceId, available, totalQuantity);
    }

    public int reloadAll() {
        log.info("[InventoryInitializer] Reloading all resources...");
        return initializeAll();
    }

    /**
     * Kiểm tra Redis có đồng bộ với DB không.
     */
    public boolean verify(String resourceId) {
        String redisValue = redisTemplate.opsForValue().get(KEY_PREFIX + resourceId);
        if (redisValue == null) {
            log.warn("[InventoryInitializer] verify FAIL: Redis key not found for {}", resourceId);
            return false;
        }

        long redisAvailable = Long.parseLong(redisValue);
        AbstractInventoryEntity entity = entityManager.find(entityClass, resourceId);
        long dbAvailable = entity != null ? entity.getAvailable() : -1L;

        boolean consistent = redisAvailable == dbAvailable;
        if (!consistent) {
            log.warn("[InventoryInitializer] verify MISMATCH: resourceId={}, redis={}, db={}",
                resourceId, redisAvailable, dbAvailable);
        }
        return consistent;
    }

    private void loadToRedis(String resourceId, long available, long total) {
        redisTemplate.opsForValue().set(KEY_PREFIX + resourceId, String.valueOf(available));
        redisTemplate.opsForValue().set(TOTAL_KEY_PREFIX + resourceId, String.valueOf(total));
    }
}
