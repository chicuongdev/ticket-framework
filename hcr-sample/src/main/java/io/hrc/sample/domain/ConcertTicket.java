package io.hrc.sample.domain;

import io.hrc.inventory.entity.AbstractInventoryEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tài nguyên: Vé concert có giới hạn số lượng.
 *
 * <p>Extends {@link AbstractInventoryEntity} để framework có thể quản lý
 * inventory (available, total, version) trực tiếp trên bảng này.
 * Developer thêm các field nghiệp vụ riêng (concertName, venue...).
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

    @Column(name = "price_per_ticket", nullable = false)
    private long pricePerTicket;

    public ConcertTicket(String resourceId, long totalTickets,
                          String concertName, String venue,
                          String eventDate, long pricePerTicket) {
        super(resourceId, totalTickets, (long) (totalTickets * 0.1));
        this.concertName = concertName;
        this.venue = venue;
        this.eventDate = eventDate;
        this.pricePerTicket = pricePerTicket;
    }
}
