package io.hrc.product.order.domain;

import io.hrc.core.domain.AbstractOrder;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "ticket_orders")
@Getter
@Setter
@NoArgsConstructor
public class TicketOrder extends AbstractOrder {

    @Column(name = "total_amount", precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "concert_name")
    private String concertName;
}
