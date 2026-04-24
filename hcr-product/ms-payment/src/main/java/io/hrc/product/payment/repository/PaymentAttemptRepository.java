package io.hrc.product.payment.repository;

import io.hrc.product.payment.domain.PaymentAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, String> {
}
