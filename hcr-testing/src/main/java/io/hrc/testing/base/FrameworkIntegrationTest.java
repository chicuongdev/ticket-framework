package io.hrc.testing.base;

/**
 * Abstract base for integration tests. Pre-wires: InMemoryEventBus, MockPaymentGateway, H2 DB.
 * Given: givenAvailableStock(), givenPaymentWillSucceed/Fail/Timeout/SucceedLate().
 * Then: thenAssertNoOversell(), thenAssertEventPublished().
 */
public abstract class FrameworkIntegrationTest {}
