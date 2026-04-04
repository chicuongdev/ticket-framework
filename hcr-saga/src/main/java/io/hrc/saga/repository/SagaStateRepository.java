package io.hrc.saga.repository;

/**
 * CRITICAL for async saga mode. Persists SagaContext to survive crashes between steps.
 * Without this: crash between Reserve and Payment -> double-charge or double-release risk.
 * Framework throws HcrFrameworkException at startup if async mode + no bean provided.
 * Recommended implementation: serialize SagaContext to JSON in orders table.
 */
public interface SagaStateRepository {}
