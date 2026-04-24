package io.hrc.product.payment.domain;

public enum PaymentAttemptStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    TIMEOUT,
    UNKNOWN
}
