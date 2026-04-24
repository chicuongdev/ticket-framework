package io.hrc.product.order;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Mở rộng entity/repository scan sang {@code io.hrc.inventory.persistence} để pick up
 * {@code ProcessedEvent} + {@code ProcessedEventRepository} (cần khi InventoryStrategy
 * factory wire P3 dependencies). {@code @EnableScheduling} bật reconciliation cycle.
 */
@SpringBootApplication
@EnableScheduling
@EntityScan(basePackages = {
        "io.hrc.product.order.domain",
        "io.hrc.inventory.persistence"
})
@EnableJpaRepositories(basePackages = {
        "io.hrc.product.order.repository",
        "io.hrc.inventory.persistence"
})
public class MsOrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsOrderApplication.class, args);
    }
}
