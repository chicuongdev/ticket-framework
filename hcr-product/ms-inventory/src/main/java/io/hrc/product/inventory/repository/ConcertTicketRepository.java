package io.hrc.product.inventory.repository;

import io.hrc.product.inventory.domain.ConcertTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConcertTicketRepository extends JpaRepository<ConcertTicket, String> {
}
