package io.hrc.saga.orchestrator.async;

import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;

/**
 * P3 style: Validate -> Idempotency -> Reserve (Redis) -> Publish event -> HTTP 202.
 * DB NOT in critical path. Async: Event Bus -> OrderCreated -> Payment -> Confirm/Cancel consumers.
 * MANDATORY: SagaStateRepository bean must exist - framework throws at startup if missing.
 */
public abstract class AsynchronousSagaOrchestrator extends AbstractSagaOrchestrator {}
