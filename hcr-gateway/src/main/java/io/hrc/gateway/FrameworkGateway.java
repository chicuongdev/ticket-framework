package io.hrc.gateway;

/**
 * Abstract entry point. Framework enforces full pipeline:
 *   Validate -> Idempotency check -> Rate limit -> Circuit breaker -> SagaOrchestrator.
 * Developer implements: validateBusinessRules().
 * Developer can override: shouldRateLimit(), getRateLimitKey(), getIdempotencyKey().
 */
public abstract class FrameworkGateway {}
