package io.hrc.sample.domain;

import io.hrc.core.domain.AbstractOrder;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Order: Đơn đặt vé concert.
 *
 * <p>Extends {@link AbstractOrder} để framework có thể điều phối Saga flow.
 * Developer thêm field nghiệp vụ: totalAmount, buyerEmail...
 */
@Entity
@Table(name = "ticket_orders")
@Getter
@Setter
@NoArgsConstructor
public class TicketOrder extends AbstractOrder {

    @Column(name = "total_amount")
    private long totalAmount;

    @Column(name = "buyer_email")
    private String buyerEmail;

    @Column(name = "concert_name")
    private String concertName;

    /** Số vé nhân đơn giá. */
    public long calculateTotal(long pricePerTicket) {
        return (long) getQuantity() * pricePerTicket;
    }
}
