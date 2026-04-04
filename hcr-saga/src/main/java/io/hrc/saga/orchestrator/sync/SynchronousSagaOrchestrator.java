package io.hrc.saga.orchestrator.sync;

import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;

/**
 * P1/P2 style: Validate -> Idempotency -> Reserve (DB) -> Charge (with timeout handler)
 *   -> Confirm -> HTTP 201.
 * Client gets final result synchronously. DB is in critical path -> throughput limited.
 */
public abstract class SynchronousSagaOrchestrator extends AbstractSagaOrchestrator {}
