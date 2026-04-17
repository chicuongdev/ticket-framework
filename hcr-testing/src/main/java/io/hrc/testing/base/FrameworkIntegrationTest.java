package io.hrc.testing.base;

import io.hrc.core.domain.AbstractOrder;
import io.hrc.core.domain.DomainEvent;
import io.hrc.core.domain.OrderRequest;
import io.hrc.eventbus.adapter.inmemory.InMemoryEventBusAdapter;
import io.hrc.payment.gateway.mock.MockPaymentGateway;
import io.hrc.saga.orchestrator.AbstractSagaOrchestrator;
import io.hrc.testing.FrameworkTestSupport;
import io.hrc.testing.inventory.InMemoryInventoryStrategy;

/**
 * Base class cho integration test của developer.
 *
 * <p>Pre-wires: {@link InMemoryInventoryStrategy}, {@link MockPaymentGateway},
 * {@link InMemoryEventBusAdapter}. Developer chỉ cần implement 3 abstract method
 * và viết test case.
 *
 * <pre>{@code
 * class ConcertTicketTest extends FrameworkIntegrationTest<TicketRequest, TicketOrder> {
 *
 *     @Override
 *     protected AbstractSagaOrchestrator<TicketRequest, TicketOrder> createOrchestrator(
 *             InMemoryInventoryStrategy inventory,
 *             MockPaymentGateway payment,
 *             InMemoryEventBusAdapter eventBus) {
 *         return new TicketBookingOrchestrator(inventory, payment, eventBus);
 *     }
 *
 *     @Override
 *     protected TicketRequest buildTestRequest(String resourceId, int quantity) {
 *         return TicketRequest.of(resourceId, quantity);
 *     }
 *
 *     @Override
 *     protected long getInitialStock() { return 100; }
 *
 *     @Test
 *     void shouldConfirmOrderWhenStockAvailable() {
 *         givenPaymentWillSucceed();
 *         AbstractOrder order = getOrchestrator().process(buildTestRequest(TEST_RESOURCE_ID, 1));
 *         thenAssertNoOversell();
 *         thenAssertEventPublished(OrderConfirmedEvent.class);
 *     }
 * }
 * }</pre>
 *
 * @param <REQ> type của request (extends OrderRequest)
 * @param <O>   type của order (extends AbstractOrder)
 */
public abstract class FrameworkIntegrationTest<
        REQ extends OrderRequest,
        O extends AbstractOrder> {

    protected static final String TEST_RESOURCE_ID = "test-resource";

    private InMemoryInventoryStrategy inventory;
    private MockPaymentGateway mockPayment;
    private InMemoryEventBusAdapter eventBus;
    private AbstractSagaOrchestrator<REQ, O> orchestrator;

    // =========================================================================
    // Setup — gọi trước mỗi test (JUnit 5: @BeforeEach)
    // =========================================================================

    /**
     * Khởi tạo infrastructure và orchestrator. Developer gọi trong {@code @BeforeEach}.
     *
     * <pre>{@code
     * @BeforeEach
     * void setup() { setUp(); }
     * }</pre>
     */
    protected void setUp() {
        inventory = FrameworkTestSupport.inMemoryInventory(TEST_RESOURCE_ID, getInitialStock());
        mockPayment = FrameworkTestSupport.mockPaymentAlwaysSuccess();
        eventBus = FrameworkTestSupport.inMemoryEventBus();
        orchestrator = createOrchestrator(inventory, mockPayment, eventBus);
    }

    // =========================================================================
    // Developer PHẢI implement
    // =========================================================================

    /**
     * Tạo orchestrator với các dependency đã được inject sẵn.
     * Developer chỉ cần gọi constructor của orchestrator cụ thể.
     */
    protected abstract AbstractSagaOrchestrator<REQ, O> createOrchestrator(
            InMemoryInventoryStrategy inventory,
            MockPaymentGateway payment,
            InMemoryEventBusAdapter eventBus);

    /**
     * Tạo test request cho resourceId và quantity cho trước.
     * Developer set các field cần thiết theo nghiệp vụ.
     */
    protected abstract REQ buildTestRequest(String resourceId, int quantity);

    /** Số lượng inventory ban đầu cho TEST_RESOURCE_ID. */
    protected abstract long getInitialStock();

    // =========================================================================
    // Given — setup scenario
    // =========================================================================

    /** Payment luôn thành công ngay lập tức. */
    protected void givenPaymentWillSucceed() {
        replacePaymentGateway(FrameworkTestSupport.mockPaymentAlwaysSuccess());
    }

    /** Payment luôn thất bại. */
    protected void givenPaymentWillFail() {
        replacePaymentGateway(FrameworkTestSupport.mockPaymentAlwaysFail());
    }

    /** Payment timeout (Tình huống A: gateway crash). */
    protected void givenPaymentWillTimeout() {
        replacePaymentGateway(FrameworkTestSupport.mockPaymentWithNoResponse(1.0));
    }

    /** Payment thành công nhưng response bị mất (Tình huống B). */
    protected void givenPaymentWillSucceedLate() {
        replacePaymentGateway(FrameworkTestSupport.mockPaymentWithLateSuccess(1.0));
    }

    /** Đặt inventory ban đầu thành quantity cho TEST_RESOURCE_ID. */
    protected void givenAvailableStock(long quantity) {
        inventory.reset();
        inventory.initialize(TEST_RESOURCE_ID, quantity);
        orchestrator = createOrchestrator(inventory, mockPayment, eventBus);
    }

    // =========================================================================
    // Then — assertions
    // =========================================================================

    /** Kiểm tra không xảy ra oversell cho TEST_RESOURCE_ID. */
    protected void thenAssertNoOversell() {
        FrameworkTestSupport.assertNoOversell(TEST_RESOURCE_ID, inventory);
    }

    /** Kiểm tra ít nhất 1 event thuộc type này đã được publish. */
    protected void thenAssertEventPublished(Class<? extends DomainEvent> eventType) {
        FrameworkTestSupport.assertEventPublished(eventBus, eventType);
    }

    /** Kiểm tra event được publish đúng expectedCount lần. */
    protected void thenAssertEventPublishedTimes(Class<? extends DomainEvent> eventType,
                                                  long expectedCount) {
        FrameworkTestSupport.assertEventPublishedTimes(eventBus, eventType, expectedCount);
    }

    // =========================================================================
    // Truy cập
    // =========================================================================

    protected AbstractSagaOrchestrator<REQ, O> getOrchestrator() {
        return orchestrator;
    }

    protected InMemoryInventoryStrategy getInventory() {
        return inventory;
    }

    protected MockPaymentGateway getMockPayment() {
        return mockPayment;
    }

    protected InMemoryEventBusAdapter getEventBus() {
        return eventBus;
    }

    // =========================================================================
    // Nội bộ
    // =========================================================================

    private void replacePaymentGateway(MockPaymentGateway newGateway) {
        mockPayment = newGateway;
        orchestrator = createOrchestrator(inventory, mockPayment, eventBus);
    }
}
