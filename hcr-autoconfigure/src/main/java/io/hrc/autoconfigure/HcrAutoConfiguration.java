package io.hrc.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;

/**
 * Conditionally creates beans based on YAML config:
 *   - InventoryStrategy: per hcr.inventory.strategy (default: pessimistic-lock)
 *   - EventBus: per hcr.event-bus.type (default: kafka)
 *   - PaymentGateway: MockPaymentGateway if no custom bean
 *   - FrameworkMetrics: MicrometerFrameworkMetrics if Micrometer present
 *   - RateLimiter: RedisTokenBucketRateLimiter (if enabled=true)
 *   - IdempotencyHandler: RedisIdempotencyHandler (always)
 *   - ReconciliationService: auto-run if configured
 */
@AutoConfiguration
public class HcrAutoConfiguration {}
