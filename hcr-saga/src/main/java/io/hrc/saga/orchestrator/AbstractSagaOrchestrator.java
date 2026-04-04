package io.hrc.saga.orchestrator;

/**
 * Template Method base for both sync and async orchestrators.
 *
 * Framework-controlled (DO NOT override): process(), retryPayment(), adminCancel(),
 *   getStatus(), processPartial(), expireReservation().
 *
 * Developer MUST implement: createOrder(), findOrder(), saveOrder(),
 *   buildPaymentRequest(), onConfirmed(), onCancelled().
 *
 * Optional lifecycle hooks: onReserving(), onPaymentProcessing(), onConfirming(),
 *   onCancelling(), onCompensating(), onExpiring().
 *
 * Config override: getReservationTimeoutMinutes() (default 5),
 *   allowPartialFulfillment() (default false).
 */
public abstract class AbstractSagaOrchestrator {}
