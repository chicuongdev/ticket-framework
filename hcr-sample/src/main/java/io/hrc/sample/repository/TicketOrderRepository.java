package io.hrc.sample.repository;

import io.hrc.sample.domain.TicketOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TicketOrderRepository extends JpaRepository<TicketOrder, String> {

    Optional<TicketOrder> findByOrderId(String orderId);
}
