package io.hrc.saga.context;

/**
 * In-memory state carrier between saga steps.
 * Avoids repeated DB queries. Fields: order, reservationResult, paymentResult,
 * completedSteps, failedSteps, metadata.
 * Serialized to JSON and persisted by SagaStateRepository in async mode.
 */
public class SagaContext {}
