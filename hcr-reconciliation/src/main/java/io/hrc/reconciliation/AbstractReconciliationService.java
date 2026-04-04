package io.hrc.reconciliation;

/**
 * Framework auto-runs on schedule (fixedDelay configurable, default 5 min).
 * Uses distributed lock so only one instance runs at a time.
 * Developer implements handlers for all 5 inconsistency cases.
 * Framework auto-publishes: ReconciliationStartedEvent, ReconciliationFixedEvent.
 *
 * 5 Cases handled:
 *   1. Stale Pending Order (payment timeout / consumer crash)
 *   2. Late Payment Success (response lost but charged)
 *   3. Inventory Mismatch (Redis != DB)
 *   4. Unpersisted Reservation (consumer lag/crash)
 *   5. Duplicate Order (idempotency bypass)
 */
public abstract class AbstractReconciliationService {}
