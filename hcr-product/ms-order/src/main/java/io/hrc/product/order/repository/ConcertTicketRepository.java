package io.hrc.product.order.repository;

import io.hrc.product.order.domain.ConcertTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertTicketRepository extends JpaRepository<ConcertTicket, String> {
}
