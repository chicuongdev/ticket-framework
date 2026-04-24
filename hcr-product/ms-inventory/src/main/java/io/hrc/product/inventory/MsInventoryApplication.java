package io.hrc.product.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Mở rộng entity/repository scan sang {@code io.hrc.inventory.persistence} để pick up
 * {@code ProcessedEvent} + {@code ProcessedEventRepository} dùng cho P3 dedup
 * (InventoryPersistenceConsumer phụ thuộc bean này).
 */
@SpringBootApplication
@EntityScan(basePackages = {
        "io.hrc.product.inventory.domain",
        "io.hrc.inventory.persistence"
})
@EnableJpaRepositories(basePackages = {
        "io.hrc.product.inventory.repository",
        "io.hrc.inventory.persistence"
})
public class MsInventoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(MsInventoryApplication.class, args);
    }
}
