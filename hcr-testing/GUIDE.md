# hcr-testing — Hướng dẫn đọc code

## Mục đích
Cho phép developer test use case của họ mà không cần Redis, Kafka, hay DB thật.
Framework cung cấp sẵn in-memory infrastructure.

## Thứ tự đọc code

1. **`inventory/InMemoryInventoryStrategy.java`** — Thread-safe inventory bằng `AtomicLong`.
   Implement đầy đủ `InventoryStrategy`. Methods kiểm tra: `getCurrentAvailable()`,
   `getReserveCallCount()`, `getOversellAttemptCount()`.

2. **`result/ConcurrencyTestResult.java`** — Result object sau test đồng thời.
   Rule bất biến: `oversellCount` phải bằng 0.

3. **`FrameworkTestSupport.java`** — Utility class.
   - Factories: `inMemoryInventory()`, `mockPayment()`, `inMemoryEventBus()`
   - `simulateConcurrentRequests()` — dùng `ExecutorService` + `CountDownLatch`
   - Assertions: `assertNoOversell()`, `assertZeroOversell()`, `assertThroughputAbove()`, `assertEventPublished()`

4. **`base/FrameworkIntegrationTest.java`** — Base class cho integration test.
   Developer extend, implement 3 abstract method, dùng `given*()` / `thenAssert*()`.

## Cách dùng nhanh

```java
class MyTest extends FrameworkIntegrationTest<MyRequest, MyOrder> {

    @BeforeEach void setup() { setUp(); }

    @Override
    protected AbstractSagaOrchestrator<MyRequest, MyOrder> createOrchestrator(
            InMemoryInventoryStrategy inv, MockPaymentGateway pay, InMemoryEventBusAdapter bus) {
        return new MyOrchestrator(inv, pay, bus);
    }

    @Override
    protected MyRequest buildTestRequest(String resourceId, int quantity) {
        return new MyRequest(resourceId, quantity);
    }

    @Override
    protected long getInitialStock() { return 100; }

    @Test
    void happyPath() {
        givenPaymentWillSucceed();
        getOrchestrator().process(buildTestRequest(TEST_RESOURCE_ID, 1));
        thenAssertNoOversell();
        thenAssertEventPublished(OrderConfirmedEvent.class);
    }
}
```

## Lưu ý quan trọng

- `InMemoryEventBusAdapter` là **synchronous** — handler chạy ngay trong thread publisher.
  Khác hoàn toàn với Kafka (async). Test kết quả có thể khác production.
- `MockPaymentGateway.build()` yêu cầu non-null `TimeoutHandler`. `FrameworkTestSupport`
  tạo TimeoutHandler với `null` gateway — nếu thực sự có timeout, sẽ NPE báo lỗi rõ ràng.
- `simulateConcurrentRequests()` không tự phát hiện oversell trong `ConcurrencyTestResult.oversellCount`.
  Dùng `assertNoOversell(resourceId, inventory)` để kiểm tra trực tiếp từ strategy.
