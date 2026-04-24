package io.hrc.product.order.domain;

import io.hrc.inventory.entity.AbstractInventoryEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Local catalog cho ms-order — giữ giá vé để build PaymentRequestedEvent.
 *
 * <p>Cùng resource_id với ConcertTicket của ms-inventory. ms-order KHÔNG ghi
 * available/version (Redis là source of truth, ms-inventory persist).
 *
 * <p>Lý do extend AbstractInventoryEntity: framework {@code RedisAtomicStrategy.initialize()}
 * nhận entityClass + entityManager — tuy nhiên ms-order KHÔNG seed Redis (ms-inventory làm),
 * nên field available/version trong bảng này thực tế không được dùng.
 */
@Entity
@Table(name = "concert_tickets")
@Getter
@Setter
@NoArgsConstructor
public class ConcertTicket extends AbstractInventoryEntity {

    @Column(name = "concert_name", nullable = false)
    private String concertName;

    @Column(name = "price_per_ticket", nullable = false, precision = 19, scale = 2)
    private BigDecimal pricePerTicket;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    public ConcertTicket(String resourceId, long totalTickets,
                          String concertName, BigDecimal pricePerTicket, String currency) {
        super(resourceId, totalTickets, 0);
        this.concertName = concertName;
        this.pricePerTicket = pricePerTicket;
        this.currency = currency;
    }
}
