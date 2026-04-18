package io.hrc.sample;

import io.hrc.autoconfigure.annotation.EnableHighConcurrencyResource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Demo: Concert Ticket Booking System.
 *
 * <p>Mở rộng entity/repository scan sang {@code io.hrc.inventory.persistence}
 * để pick up {@code ProcessedEvent} + {@code ProcessedEventRepository} dùng cho P3 dedup.
 */
@SpringBootApplication
@EnableHighConcurrencyResource
@EntityScan(basePackages = {"io.hrc.sample.domain", "io.hrc.inventory.persistence"})
@EnableJpaRepositories(basePackages = {"io.hrc.sample.repository", "io.hrc.inventory.persistence"})
public class SampleApplication {
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}
