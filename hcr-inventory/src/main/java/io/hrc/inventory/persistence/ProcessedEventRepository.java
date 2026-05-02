package io.hrc.inventory.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * Xóa các entry cũ hơn cutoff. Dùng bởi {@link ProcessedEventsCleanupJob}
     * để giới hạn kích thước bảng dedup.
     *
     * @return số rows đã xóa
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ProcessedEvent p WHERE p.processedAt < :cutoff")
    int deleteByProcessedAtBefore(@Param("cutoff") Instant cutoff);
}
