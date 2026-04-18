package io.hrc.sample.config;

import io.hrc.inventory.strategy.InventoryStrategy;
import io.hrc.sample.domain.ConcertTicket;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Seed Redis từ DB lúc startup khi strategy=redis-atomic.
 *
 * <p>Lý do tồn tại: P3 dùng Redis làm source-of-truth, nhưng data vẫn nằm trong DB
 * (qua {@code data.sql}). Cần một bước "warm-up" để copy {@code available} từ DB → Redis
 * trước khi nhận traffic.
 *
 * <p>Bean chỉ được tạo khi {@code hcr.inventory.strategy=redis-atomic} → P1/P2 không bị ảnh hưởng.
 */
@Component
@ConditionalOnProperty(prefix = "hcr.inventory", name = "strategy", havingValue = "redis-atomic")
@Slf4j
public class RedisInventorySeeder implements ApplicationRunner {

    @PersistenceContext
    private EntityManager entityManager;

    private final InventoryStrategy inventoryStrategy;

    public RedisInventorySeeder(InventoryStrategy inventoryStrategy) {
        this.inventoryStrategy = inventoryStrategy;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<ConcertTicket> tickets = entityManager
                .createQuery("SELECT t FROM ConcertTicket t", ConcertTicket.class)
                .getResultList();

        for (ConcertTicket t : tickets) {
            inventoryStrategy.initialize(t.getResourceId(), t.getAvailable());
            log.info("[Seeder] Initialized Redis: resourceId={}, available={}",
                    t.getResourceId(), t.getAvailable());
        }
        log.info("[Seeder] Done — {} resources seeded into Redis", tickets.size());
    }
}
