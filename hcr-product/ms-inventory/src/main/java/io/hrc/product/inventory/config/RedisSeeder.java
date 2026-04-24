package io.hrc.product.inventory.config;

import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.product.inventory.domain.ConcertTicket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Warm-up Redis từ Postgres lúc startup.
 *
 * <p><b>Idempotent:</b> chỉ initialize những key CHƯA tồn tại trong Redis.
 * Restart app không bị crash vì framework's {@code initialize()} throw khi key
 * đã có. Đảm bảo Redis state không bị overwrite vô ý.
 *
 * <p>Để force re-seed: {@code redis-cli FLUSHALL} (hoặc DEL từng key) trước khi restart.
 */
@Component
@Slf4j
public class RedisSeeder implements ApplicationRunner {

    private static final String INVENTORY_KEY_PREFIX = "hcr:inventory:";

    @PersistenceContext
    private EntityManager entityManager;

    private final InventoryStrategy inventoryStrategy;
    private final StringRedisTemplate redis;

    public RedisSeeder(InventoryStrategy inventoryStrategy, StringRedisTemplate redis) {
        this.inventoryStrategy = inventoryStrategy;
        this.redis = redis;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ConcertTicket> tickets = entityManager
                .createQuery("SELECT t FROM ConcertTicket t", ConcertTicket.class)
                .getResultList();

        int seeded = 0;
        int skipped = 0;
        for (ConcertTicket t : tickets) {
            String key = INVENTORY_KEY_PREFIX + t.getResourceId();
            if (Boolean.TRUE.equals(redis.hasKey(key))) {
                String current = redis.opsForValue().get(key);
                log.info("[Seeder] Skip (already exists): resourceId={}, redisValue={}",
                        t.getResourceId(), current);
                skipped++;
                continue;
            }
            inventoryStrategy.initialize(t.getResourceId(), t.getAvailable());
            log.info("[Seeder] Initialized Redis: resourceId={}, available={}",
                    t.getResourceId(), t.getAvailable());
            seeded++;
        }
        log.info("[Seeder] Done — seeded={}, skipped={}, total={}",
                seeded, skipped, tickets.size());
    }
}
