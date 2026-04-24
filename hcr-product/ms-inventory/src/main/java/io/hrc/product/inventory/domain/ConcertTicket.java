package io.hrc.product.inventory.domain;

import io.hrc.inventory.entity.AbstractInventoryEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Concert ticket inventory — bảng nguồn cho ms-inventory's Postgres.
 *
 * <p>Field từ {@link AbstractInventoryEntity}: resourceId, total, available, version,
 * lowStockThreshold, createdAt, updatedAt.
 */
@Entity
@Table(name = "concert_tickets")
@Getter
@Setter
@NoArgsConstructor
public class ConcertTicket extends AbstractInventoryEntity {

    @Column(name = "concert_name", nullable = false)
    private String concertName;

    @Column(name = "venue", nullable = false)
    private String venue;

    @Column(name = "event_date", nullable = false)
    private String eventDate;

    public ConcertTicket(String resourceId, long totalTickets,
                          String concertName, String venue, String eventDate) {
        super(resourceId, totalTickets, (long) (totalTickets * 0.1));
        this.concertName = concertName;
        this.venue = venue;
        this.eventDate = eventDate;
    }
}
