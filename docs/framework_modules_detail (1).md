# HCR Framework — Tài liệu chi tiết 10 Module

> **Mục đích:** Mô tả chi tiết vai trò, trách nhiệm, thành phần và
> mối quan hệ của từng module trong HCR Framework.
> Tài liệu này KHÔNG chứa code — chỉ mô tả khái niệm và thiết kế.
>
> **Phiên bản:** 0.1.0-SNAPSHOT
> **Cập nhật:** 04/2026 — Revision 2 (cập nhật theo Critical Review)
> **Dự án:** SOICT — HUST

> **Thay đổi trong Revision 2:**
> - Thêm section **Consistency Guarantees** (Module 01) — định nghĩa
>   rõ consistency window cho P1/P2/P3
> - Thêm `correlationId` vào `DomainEvent` + `CorrelationIdFilter`
>   (Module 01, Module 10) — distributed tracing cơ bản
> - Thêm `EventBusCapabilities` (Module 05) — document sự khác biệt
>   4 adapter, cảnh báo khi capability mismatch
> - Strengthen `SagaStateRepository` (Module 03) — bắt buộc khi
>   dùng async mode, fail fast khi startup thiếu
> - Thêm `HcrActuatorEndpoint` (Module 10) — expose `/actuator/hcr`
>   để debug config đang active

---

## Mục lục

1. [Module 01 — Core Domain](#module-01--core-domain)
2. [Module 02 — Inventory](#module-02--inventory)
3. [Module 03 — Saga Orchestration](#module-03--saga-orchestration)
4. [Module 04 — Payment](#module-04--payment)
5. [Module 05 — Event Bus](#module-05--event-bus)
6. [Module 06 — Gateway](#module-06--gateway)
7. [Module 07 — Reconciliation](#module-07--reconciliation)
8. [Module 08 — Observability](#module-08--observability)
9. [Module 09 — Testing Support](#module-09--testing-support)
10. [Module 10 — Auto Configuration](#module-10--auto-configuration)

---

---

# Module 01 — Core Domain

---

## 1.1 Tổng quan

### Vai trò trong hệ thống

Core Domain là **nền móng của toàn bộ framework**. Module này định
nghĩa "ngôn ngữ chung" mà tất cả các module khác sử dụng để giao
tiếp với nhau. Không có Core Domain, các module không thể hiểu
nhau — giống như hai người nói hai thứ tiếng khác nhau.

### Tại sao cần thiết kế trong framework?

Nếu không có domain model chuẩn hóa, mỗi developer sẽ tự định
nghĩa "resource", "order", "status" theo cách riêng của mình.
Hệ quả:

- Module Inventory không biết "resource" của Gateway là gì
- Module Payment không biết "order" của Saga là gì
- Không thể reuse bất kỳ component nào giữa các use case

Core Domain giải quyết vấn đề này bằng cách cung cấp một tập hợp
các kiểu dữ liệu chuẩn, bắt buộc mọi use case phải dùng. Developer
được extend để thêm field riêng, nhưng không được bỏ qua các field
bắt buộc.

### Nguyên tắc thiết kế

- **Abstract, không concrete:** Tất cả domain object chính đều là
  abstract class — developer bắt buộc phải extend, không thể dùng
  trực tiếp.
- **Minimal mandatory fields:** Chỉ những field nào mà TOÀN BỘ
  use case đều cần mới được đưa vào framework. Field riêng của
  từng use case do developer tự thêm.
- **Immutable where possible:** Enum, Result object không thay đổi
  sau khi tạo — tránh bug do mutation.
- **Fail fast:** ValidationResult throw exception ngay lập tức thay
  vì để lỗi lan rộng.

---

## 1.2 Consistency Guarantees

> **Đây là hợp đồng chính thức giữa framework và developer.**
> Developer cần đọc section này trước khi chọn strategy để biết
> mình đang chấp nhận mức độ consistency nào.

### Định nghĩa các khái niệm

**Consistency Window:** Khoảng thời gian tối đa mà hệ thống có thể
ở trạng thái không nhất quán sau một thao tác. Sau khoảng thời gian
này, hệ thống đảm bảo đã trở về trạng thái nhất quán.

**Source of Truth:** Nơi lưu trữ được coi là đúng tuyệt đối khi
có conflict giữa các storage.

**Reconciliation Guarantee:** Cam kết về thời gian tối đa để
Reconciliation phát hiện và sửa inconsistency.

---

### Consistency Model theo từng Strategy

#### P1 — PessimisticLockStrategy

| Thuộc tính | Giá trị |
|------------|---------|
| Mức consistency | **Strong Consistency** |
| Consistency window | **0ms** |
| Source of truth | PostgreSQL (duy nhất) |
| Có thể read stale data không? | Không |
| Hiển thị inventory realtime được không? | Có, luôn chính xác |
| Reconciliation có cần không? | Chỉ cho payment edge case |

**Cam kết:** Khi `reserve()` trả về SUCCESS, dữ liệu đã được ghi
xuống DB và commit. Mọi read sau đó đều thấy giá trị mới nhất.

**Đánh đổi:** Throughput bị giới hạn bởi DB connection pool và
lock contention.

---

#### P2 — OptimisticLockStrategy

| Thuộc tính | Giá trị |
|------------|---------|
| Mức consistency | **Strong Consistency** |
| Consistency window | **0ms** |
| Source of truth | PostgreSQL (duy nhất) |
| Có thể read stale data không? | Không |
| Hiển thị inventory realtime được không? | Có, luôn chính xác |
| Reconciliation có cần không? | Chỉ cho payment edge case |

**Cam kết:** Tương tự P1. Khi `reserve()` trả về SUCCESS sau khi
vượt qua version check, dữ liệu đã được commit.

**Đánh đổi:** Có thể có retry storm khi tải cao — throughput không
ổn định dưới spike.

---

#### P3 — RedisAtomicStrategy

| Thuộc tính | Giá trị |
|------------|---------|
| Mức consistency | **Eventual Consistency** |
| Consistency window (normal) | **< 1 giây** (Kafka consumer lag) |
| Consistency window (worst case) | **≤ 5 phút** (Reconciliation timeout) |
| Source of truth | **Redis** (cho inventory count) |
| Có thể read stale data không? | **Có** — DB có thể lag sau Redis |
| Hiển thị inventory realtime được không? | Chỉ khi đọc từ Redis trực tiếp |
| Reconciliation có cần không? | **Có — bắt buộc** |

**Cam kết:**
- Khi `reserve()` trả về SUCCESS: Redis đã ghi atomic, oversell
  **không thể xảy ra**.
- DB sẽ được sync trong vòng **< 1 giây** trong điều kiện bình
  thường (Kafka consumer hoạt động tốt).
- Trong trường hợp xấu nhất (consumer crash, Kafka lag), DB sẽ
  được sync trong vòng **≤ 5 phút** bởi Reconciliation.
- Sau AOF flush (mặc định: mỗi 1 giây), dữ liệu Redis được đảm
  bảo persistent. Trong window 1 giây này, nếu Redis crash, có thể
  mất tối đa 1 giây inventory count.

**Đánh đổi:** Throughput cao nhất, nhưng developer phải chấp nhận:
- DB có thể hiển thị số lượng cũ trong thời gian ngắn
- Cần có Reconciliation chạy định kỳ
- Cần Redis AOF enabled để đảm bảo persistence

---

### Consistency Model cho Order State

Bất kể dùng strategy nào, order state có consistency model riêng:

| Saga Mode | Khi nào client biết kết quả cuối? | Consistency |
|-----------|----------------------------------|-------------|
| Synchronous (P1/P2) | Ngay trong HTTP response | Strong |
| Asynchronous (P3) | Sau khi poll hoặc nhận webhook | Eventual, ≤ vài giây |

---

### Consistency Model cho Payment

Payment luôn là eventual với reconciliation guarantee:

```
Trường hợp bình thường:
  charge() → SUCCESS/FAILED → kết quả ngay trong Saga flow

Trường hợp timeout (T/H A và B):
  charge() timeout → TimeoutHandler polling ≤ 30 giây
  → Nếu vẫn UNKNOWN → order ở PENDING
  → Reconciliation giải quyết trong ≤ 5 phút
```

---

### Hướng dẫn chọn strategy

```
Yêu cầu Strong Consistency tuyệt đối?
  → P1 hoặc P2
  → Không cần Reconciliation cho inventory

Chấp nhận Eventual Consistency, ưu tiên throughput?
  → P3
  → BẮT BUỘC phải có Reconciliation chạy
  → BẮT BUỘC phải có Redis AOF enabled
  → KHÔNG nên hiển thị inventory count từ DB realtime
    (dùng Redis trực tiếp)
```

---

## 1.3 Vị trí trong kiến trúc

```
Core Domain
    ↑ được dùng bởi TẤT CẢ module khác
    
Inventory   ← dùng AbstractResource, ReservationResult
Saga        ← dùng AbstractOrder, OrderRequest, OrderStatus
Payment     ← dùng PaymentResult, FailureReason
Event Bus   ← dùng DomainEvent
Gateway     ← dùng ValidationResult, OrderRequest
Reconcil.   ← dùng InventorySnapshot, AbstractOrder
Observ.     ← dùng InventoryMetrics
Testing     ← dùng tất cả
```

---



### AbstractResource *(abstract class)*

**Mô tả:** Đại diện cho bất kỳ "tài nguyên có giới hạn" nào trong
hệ thống — vé concert, phòng khách sạn, slot khám bệnh, sản phẩm
flash sale. Đây là abstraction cốt lõi giúp framework không bị
coupled với một loại tài nguyên cụ thể.

**Fields bắt buộc (framework quản lý):**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| resourceId | String | Định danh duy nhất của tài nguyên |
| totalQuantity | long | Tổng số lượng ban đầu |
| availableQuantity | long | Số lượng còn có thể đặt |
| status | ResourceStatus | Trạng thái hiện tại |
| createdAt | Instant | Thời điểm tạo |
| updatedAt | Instant | Thời điểm cập nhật cuối |

**Các hành vi (methods):**

| Method | Mô tả |
|--------|-------|
| getResourceId() | Trả về ID tài nguyên |
| getTotalQuantity() | Trả về tổng số lượng |
| getAvailableQuantity() | Trả về số lượng còn lại |
| getStatus() | Trả về trạng thái |
| isAvailable() | Kiểm tra còn tài nguyên không |
| isLowStock(threshold) | Kiểm tra có dưới ngưỡng không |
| isDepleted() | Kiểm tra đã hết chưa |
| validate() | Developer override để custom validation |

**Developer extend thêm:**
- ConcertTicket: concertName, venue, date, seatCategory, price
- HotelRoom: roomType, hotelId, checkInDate, checkOutDate, pricePerNight
- FlashSaleProduct: productId, productName, discountPercent, maxPerUser

---

### AbstractOrder *(abstract class)*

**Mô tả:** Đại diện cho một "yêu cầu đặt tài nguyên" — một đơn
hàng trong hệ thống. Chứa đầy đủ thông tin để tracking lifecycle
từ lúc tạo đến khi kết thúc.

**Fields bắt buộc (framework quản lý):**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| orderId | String | Định danh duy nhất (UUID) |
| resourceId | String | Tài nguyên được đặt |
| requesterId | String | Người đặt (userId, customerId...) |
| quantity | int | Số lượng đặt |
| status | OrderStatus | Trạng thái hiện tại |
| idempotencyKey | String | Chống duplicate request |
| failureReason | String | Lý do fail (nếu có) |
| createdAt | Instant | Thời điểm tạo |
| updatedAt | Instant | Thời điểm cập nhật cuối |
| expiresAt | Instant | Thời điểm hết hạn giữ chỗ |

**Các hành vi (methods):**

| Method | Mô tả |
|--------|-------|
| getOrderId() | Trả về ID order |
| getResourceId() | Trả về ID tài nguyên |
| getRequesterId() | Trả về ID người đặt |
| getQuantity() | Trả về số lượng đặt |
| getStatus() | Trả về trạng thái |
| getIdempotencyKey() | Trả về idempotency key |
| isPending() | Kiểm tra đang ở trạng thái PENDING |
| isExpired() | Kiểm tra đã hết hạn chưa |
| isTerminal() | Kiểm tra đã kết thúc (CONFIRMED/CANCELLED/EXPIRED) |

**Developer extend thêm:**
- ConcertOrder: seatNumbers, concertName, ticketCategory, totalAmount
- HotelBooking: guestName, checkInDate, checkOutDate, specialRequests
- FlashSaleOrder: productName, originalPrice, discountedPrice, shippingAddress

---

### OrderRequest *(abstract class)*

**Mô tả:** Input vào Saga Orchestrator — đại diện cho yêu cầu
chưa được xử lý từ phía client. Khác với AbstractOrder (đã được
tạo trong DB), OrderRequest là dữ liệu thô đến từ request.

**Fields bắt buộc:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| resourceId | String | Tài nguyên muốn đặt |
| requesterId | String | Người gửi yêu cầu |
| quantity | int | Số lượng muốn đặt |
| idempotencyKey | String | Key chống duplicate |

**Các hành vi (methods):**

| Method | Mô tả |
|--------|-------|
| getResourceId() | Trả về ID tài nguyên |
| getRequesterId() | Trả về ID người yêu cầu |
| getQuantity() | Trả về số lượng |
| getIdempotencyKey() | Trả về idempotency key |
| validateRequest() | Developer override để validate nghiệp vụ |

---

### OrderStatus *(enum)*

**Mô tả:** Chuẩn hóa toàn bộ lifecycle của một order. Framework
quy định chỉ có đúng 6 trạng thái này — không thêm, không bớt.

**Các trạng thái:**

| Trạng thái | Ý nghĩa | Có thể chuyển sang |
|------------|---------|-------------------|
| PENDING | Vừa tạo, đang chờ xử lý | RESERVED, CANCELLED |
| RESERVED | Đã giữ chỗ, đang chờ thanh toán | CONFIRMED, CANCELLED, EXPIRED |
| CONFIRMED | Hoàn thành thành công (terminal) | — |
| CANCELLED | Đã hủy (terminal) | — |
| EXPIRED | Giữ chỗ hết hạn (terminal) | — |
| COMPENSATING | Đang trong quá trình rollback | CANCELLED |

**Lưu ý:** CONFIRMED, CANCELLED, EXPIRED là terminal state —
framework không cho phép chuyển sang trạng thái khác.

---

### ResourceStatus *(enum)*

**Mô tả:** Trạng thái của tài nguyên trong hệ thống. Khác với
OrderStatus (trạng thái của đơn hàng), ResourceStatus mô tả
khả năng bán của tài nguyên.

**Các trạng thái:**

| Trạng thái | Ý nghĩa | Trigger |
|------------|---------|---------|
| ACTIVE | Đang bán bình thường | Mặc định khi initialize |
| LOW_STOCK | Sắp hết (dưới ngưỡng threshold) | Framework tự detect |
| DEPLETED | Hết hàng, không thể đặt thêm | Framework tự detect |
| DEACTIVATED | Ngừng bán (do admin) | Developer gọi deactivate() |

---

### FailureReason *(enum)*

**Mô tả:** Chuẩn hóa lý do thất bại trong toàn bộ framework.
Được dùng trong: order.failureReason, ReservationResult, StepResult,
DomainEvent, và metrics.

**Các lý do:**

| Lý do | Ý nghĩa | Xảy ra ở đâu |
|-------|---------|--------------|
| INSUFFICIENT_INVENTORY | Không đủ tài nguyên để đặt | Inventory module |
| PAYMENT_FAILED | Thanh toán thất bại | Payment module |
| PAYMENT_TIMEOUT | Gateway không phản hồi trong thời gian quy định | Payment module |
| PAYMENT_UNKNOWN | Không rõ kết quả (trigger reconciliation) | Payment module |
| DUPLICATE_REQUEST | idempotencyKey đã được xử lý | Gateway module |
| VALIDATION_FAILED | Input không hợp lệ | Gateway module |
| RESERVATION_EXPIRED | Giữ chỗ hết hạn trước khi thanh toán | Reconciliation |
| SYSTEM_ERROR | Lỗi hệ thống không xác định | Bất kỳ đâu |

---

### ReservationResult *(class)*

**Mô tả:** Kết quả trả về từ mọi thao tác reserve/release inventory.
Được thiết kế theo pattern Result Object — không throw exception mà
trả về object chứa trạng thái và thông tin chi tiết.

**Fields:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| status | ReservationStatus | SUCCESS / INSUFFICIENT / ERROR |
| resourceId | String | ID tài nguyên |
| requestedQuantity | int | Số lượng yêu cầu |
| remainingAfter | long | Tồn kho còn lại sau khi reserve |
| reservedAt | Instant | Thời điểm reserve thành công |
| errorMessage | String | Thông báo lỗi (nếu có) |

**Factory methods:**

| Method | Khi dùng |
|--------|---------|
| success(...) | Reserve thành công |
| insufficient(...) | Không đủ tồn kho |
| error(...) | Lỗi hệ thống |

---

### DomainEvent *(abstract class)*

**Mô tả:** Base class cho TẤT CẢ event được publish lên Event Bus.
Mọi event trong framework đều extend từ class này — đảm bảo mọi
event đều có đủ thông tin để trace và debug.

**Fields bắt buộc:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| eventId | String | UUID, unique toàn hệ thống |
| eventType | String | Tên class của event (auto-set) |
| resourceId | String | Tài nguyên liên quan |
| orderId | String | Order liên quan |
| occurredAt | Instant | Thời điểm event xảy ra |
| retryCount | int | Số lần đã retry (cho at-least-once) |
| correlationId | String | ID để trace 1 request xuyên suốt toàn hệ thống |

**Lưu ý về correlationId:**
`correlationId` là field quan trọng cho distributed tracing. Framework
tự động sinh và propagate field này theo nguyên tắc:
- Request đầu tiên vào Gateway: framework tự sinh correlationId mới
  (UUID) nếu client không cung cấp, hoặc dùng giá trị từ HTTP header
  `X-Correlation-ID` nếu có.
- Mọi event được publish từ cùng 1 request đều mang cùng correlationId.
- Mọi log statement trong framework đều include correlationId vào MDC
  (Mapped Diagnostic Context) → developer có thể grep log theo
  correlationId để trace toàn bộ luồng xử lý của 1 request.
- correlationId được forward vào PaymentGateway request (HTTP header)
  → có thể correlate với log phía gateway bên thứ 3.

**Lưu ý về retryCount:** Framework tự tăng mỗi khi consumer không
ack và event được deliver lại. Developer không cần tự quản lý field
này.

---

### InventorySnapshot *(class)*

**Mô tả:** Ảnh chụp trạng thái inventory tại một thời điểm cụ thể.
Được dùng trong Reconciliation để so sánh Redis vs DB, và trong
Observability để monitor realtime.

**Fields:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| resourceId | String | ID tài nguyên |
| totalQuantity | long | Tổng số lượng |
| availableQuantity | long | Số lượng còn available |
| reservedQuantity | long | Đang được giữ chỗ (chưa confirm) |
| confirmedQuantity | long | Đã confirm thành công |
| snapshotAt | Instant | Thời điểm snapshot |
| source | String | Nguồn dữ liệu: "redis" hoặc "database" |

**Các hành vi (methods):**

| Method | Mô tả |
|--------|-------|
| getReservedQuantity() | Số đang giữ chỗ |
| getConfirmedQuantity() | Số đã confirm |
| isConsistentWith(other) | So sánh với snapshot khác (dùng trong reconciliation) |
| getDelta(other) | Tính độ lệch so với snapshot khác |

---

### ValidationResult *(class)*

**Mô tả:** Kết quả validation — chứa danh sách lỗi chi tiết nếu
có. Dùng trong Gateway để validate request trước khi xử lý.

**Fields:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| valid | boolean | Có hợp lệ không |
| errors | List\<ValidationError\> | Danh sách lỗi chi tiết |

**ValidationError gồm:** field (tên field lỗi), message (thông báo
lỗi), rejectedValue (giá trị bị reject).

**Các hành vi (methods):**

| Method | Mô tả |
|--------|-------|
| ok() | Factory: tạo result hợp lệ |
| fail(field, message) | Factory: tạo result với 1 lỗi |
| isValid() | Kiểm tra có hợp lệ không |
| getErrors() | Lấy danh sách lỗi |
| merge(other) | Gộp 2 ValidationResult (dùng khi validate nhiều tầng) |
| throwIfInvalid() | Throw ValidationException nếu không hợp lệ |

---

### FrameworkException *(class)*

**Mô tả:** Base exception của framework. Tất cả exception do
framework ném ra đều extend từ class này — developer có thể
catch một lần để xử lý tất cả lỗi framework.

**Fields:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| reason | FailureReason | Lý do lỗi chuẩn hóa |
| resourceId | String | Tài nguyên liên quan |
| orderId | String | Order liên quan (nếu có) |

**Các subclass:**

| Exception | Khi nào được ném |
|-----------|-----------------|
| InsufficientInventoryException | Reserve fail vì hết hàng |
| PaymentException | Payment gateway lỗi |
| IdempotencyException | Duplicate request |
| ValidationException | Input không hợp lệ |
| ReconciliationException | Reconciliation gặp lỗi nghiêm trọng |

---

---

# Module 02 — Inventory

---

## 2.1 Tổng quan

### Vai trò trong hệ thống

Inventory module là **trái tim của framework** — giải quyết bài
toán cốt lõi: làm thế nào để nhiều người đồng thời đặt tài nguyên
mà không bị oversell? Đây là phần kỹ thuật phức tạp nhất, dễ sai
nhất, và có hậu quả nghiêm trọng nhất nếu implement sai.

### Tại sao cần thiết kế trong framework?

Race condition, oversell, lost update đều xảy ra ở tầng inventory.
Nếu để developer tự implement, rủi ro rất cao:
- Quên dùng transaction
- Dùng sai isolation level
- Thiếu check available trước khi update
- Không handle concurrent update đúng cách

Framework đóng gói sẵn 3 strategy đã được kiểm chứng, mỗi cái
phù hợp với một dải tải khác nhau. Developer chỉ cần chọn strategy
qua config — không cần hiểu chi tiết bên trong.

### Ba chiều đánh đổi của 3 strategy

```
                Throughput
                    ↑
         P3 Redis ──┤ (cao nhất, eventual consistency)
                    │
         P2 Optim ──┤ (trung bình, strong consistency)
                    │
         P1 Pessi ──┤ (thấp nhất, strong consistency)
                    └──────────────────→ Complexity
                    
Strong ←──────────────────────────→ Eventual
Consistency                         Consistency
```

### Nguyên tắc thiết kế

- **Strategy Pattern:** Ba cơ chế được đóng gói thành 3
  implementation của cùng 1 interface. Saga Orchestrator chỉ biết
  interface — không biết đang dùng P1, P2 hay P3.
- **Decorator Pattern:** Circuit Breaker được wrap bên ngoài bất kỳ
  strategy nào — không thay đổi behavior của strategy.
- **Zero oversell guarantee:** Tất cả 3 strategy đều đảm bảo
  không bao giờ để available < 0.

---

## 2.2 Vị trí trong kiến trúc

```
Saga Orchestration
    │ gọi qua interface
    ↓
InventoryStrategy (interface)
    ├── PessimisticLockStrategy  ─── PostgreSQL (SELECT FOR UPDATE)
    ├── OptimisticLockStrategy   ─── PostgreSQL (version field)
    └── RedisAtomicStrategy      ─── Redis (Lua DECR) + Event Bus → DB

CircuitBreakerInventoryDecorator
    └── wrap bất kỳ strategy nào ở trên
    
InventoryStrategyFactory
    └── tạo đúng strategy dựa trên config

InventoryInitializer
    └── load DB → Redis khi startup (chỉ cho RedisAtomicStrategy)
```

---

## 2.3 Danh sách thành phần

### InventoryStrategy *(interface — trung tâm module)*

**Mô tả:** Contract chuẩn mà tất cả strategy phải tuân theo. Đây
là "hợp đồng" giữa framework và các strategy implementation.
Saga Orchestrator chỉ phụ thuộc vào interface này — có thể đổi
strategy mà không cần sửa code Saga.

**Nhóm Core Operations:**

| Method | Mô tả | Ghi chú |
|--------|-------|---------|
| reserve(resourceId, requestId, quantity) | Giữ chỗ atomic | Không oversell |
| release(resourceId, requestId, quantity) | Giải phóng chỗ | Compensating action |

**Nhóm Query:**

| Method | Mô tả |
|--------|-------|
| getAvailable(resourceId) | Số lượng còn lại |
| isAvailable(resourceId) | Còn ít nhất 1 không |
| isAvailable(resourceId, quantity) | Còn đủ quantity không |
| getSnapshot(resourceId) | Trạng thái đầy đủ tại thời điểm này |

**Nhóm Management:**

| Method | Mô tả |
|--------|-------|
| initialize(resourceId, totalQuantity) | Khởi tạo inventory mới |
| restock(resourceId, quantity) | Thêm tồn kho |
| deactivate(resourceId) | Ngừng bán |

**Nhóm Bulk Operations:**

| Method | Mô tả | Khi dùng |
|--------|-------|---------|
| reserveBatch(Map resourceId→quantity) | Reserve nhiều resource cùng lúc | Flash sale nhiều sản phẩm |
| releaseBatch(Map resourceId→quantity) | Release nhiều resource | Rollback batch |

**Nhóm Monitoring:**

| Method | Mô tả |
|--------|-------|
| isLowStock(resourceId, threshold) | Dưới ngưỡng cảnh báo không |
| getMetrics(resourceId) | Throughput, abort rate, success rate |
| getStrategyName() | Tên strategy — dùng trong metrics |

---

### PessimisticLockStrategy *(class)*

**Mô tả:** Chiến lược khóa bi quan — giả định conflict LUÔN XẢY RA,
nên lock resource trước khi đọc. Đây là chiến lược an toàn nhất
nhưng throughput thấp nhất.

**Cơ chế hoạt động:**
```
reserve():
  1. BEGIN TRANSACTION
  2. SELECT * FROM inventory WHERE resource_id=? FOR UPDATE
     → Thread khác bị BLOCK tại đây cho đến khi transaction này kết thúc
  3. Kiểm tra available >= quantity
     → Nếu không: ROLLBACK → trả về INSUFFICIENT
  4. UPDATE inventory SET available = available - quantity
  5. COMMIT → lock được giải phóng
```

**Ưu điểm:**
- Strong consistency tuyệt đối
- Không bao giờ oversell
- Đơn giản, dễ debug
- Developer dễ hiểu

**Nhược điểm:**
- Throughput giảm tuyến tính khi tải tăng
- Thread bị block → connection pool cạn kiệt ở tải cao
- Deadlock risk nếu reserve nhiều resource cùng lúc (cần order lock)

**Phù hợp khi:**
- Tải < ~1000 req/s
- Strong consistency bắt buộc
- Team ít kinh nghiệm distributed systems

**Infrastructure cần:** PostgreSQL (hoặc bất kỳ RDBMS nào hỗ trợ
SELECT FOR UPDATE)

---

### OptimisticLockStrategy *(class)*

**Mô tả:** Chiến lược khóa lạc quan — giả định conflict ÍT KHI
XẢY RA, nên không lock khi đọc. Chỉ kiểm tra conflict khi ghi.
Nếu có conflict → retry với exponential backoff + jitter.

**Cơ chế hoạt động:**
```
reserve():
  for attempt = 1..maxRetries:
    1. SELECT * FROM inventory WHERE resource_id=? (version=N)
    2. Kiểm tra available >= quantity (không lock)
       → Nếu không: trả về INSUFFICIENT ngay
    3. UPDATE inventory 
       SET available=available-quantity, version=version+1
       WHERE resource_id=? AND version=N AND available>=quantity
    4. Nếu updated = 1 → SUCCESS
    5. Nếu updated = 0 → thread khác đã update trước → RETRY
       delay = min(baseDelay * 2^attempt + jitter, maxDelay)
  → Sau maxRetries lần: trả về ERROR
```

**Ưu điểm:**
- Throughput cao hơn P1 khi conflict rate thấp
- Không block thread
- Strong consistency

**Nhược điểm:**
- Retry storm khi tải cao (nhiều thread cùng retry → cùng conflict lại)
- Throughput giảm nhanh khi conflict rate tăng
- maxRetries cần tune cẩn thận

**Phù hợp khi:**
- Tải 1000–5000 req/s
- Conflict rate < 20%
- Strong consistency bắt buộc

**Config quan trọng:**
- maxRetries: số lần retry tối đa (default: 3)
- baseDelayMs: delay cơ bản (default: 100ms)
- maxDelayMs: delay tối đa (default: 1000ms)

**Infrastructure cần:** PostgreSQL với version field trong schema

---

### RedisAtomicStrategy *(class)*

**Mô tả:** Chiến lược Redis atomic — dùng Lua script để đảm bảo
tính atomic của thao tác đọc-kiểm tra-giảm. Loại bỏ DB khỏi
critical path bằng cách persist async qua Event Bus.

**Cơ chế hoạt động:**
```
reserve() — critical path (sync, < 5ms):
  1. Thực thi Lua script trên Redis (atomic):
     - GET inventory:{resourceId}
     - Nếu không tồn tại → return -1 (lỗi, key chưa được init)
     - Nếu value < quantity → return 0 (hết hàng)
     - DECRBY inventory:{resourceId} quantity
     - return 1 (thành công)
  2. Publish ResourceReservedEvent lên Event Bus

async path (qua Event Bus):
  3. Consumer nhận ResourceReservedEvent
  4. UPDATE inventory SET available=available-quantity IN DB
  5. Acknowledge event
```

**Ưu điểm:**
- Throughput cao nhất (Redis in-memory, single-threaded)
- Không block thread
- DB không nằm trong critical path

**Nhược điểm:**
- Eventual consistency: Redis và DB có thể lệch trong window ngắn
- Redis là SPOF nếu không có Sentinel/Cluster
- Phức tạp hơn: cần Event Bus, consumer, reconciliation
- Nếu Redis crash: có thể mất inventory count (AOF giảm thiểu)

**Phù hợp khi:**
- Tải > 5000 req/s
- Eventual consistency chấp nhận được
- Có Redis infrastructure sẵn
- Team có kinh nghiệm async systems

**Infrastructure cần:** Redis (với AOF enabled), Event Bus

**Lưu ý quan trọng về tính atomic:**
Lua script được Redis thực thi atomically — Redis không chạy bất
kỳ command nào khác trong khi script đang chạy. Điều này đảm bảo
không có race condition giữa GET và DECRBY.

---

### CircuitBreakerInventoryDecorator *(class)*

**Mô tả:** Decorator wrap bất kỳ InventoryStrategy nào, thêm Circuit
Breaker behavior mà không thay đổi strategy gốc. Khi inventory
service (Redis hoặc DB) bị degraded, CB tự động mở để tránh
cascade failure.

**Ba trạng thái Circuit Breaker:**

| Trạng thái | Ý nghĩa | Hành vi |
|------------|---------|---------|
| CLOSED | Bình thường | Tất cả request đi qua strategy |
| OPEN | Đang lỗi | Reject ngay lập tức, không gọi strategy |
| HALF_OPEN | Đang kiểm tra | Cho phép một số request thử lại |

**Cơ chế chuyển trạng thái:**
```
CLOSED → OPEN:    khi error rate vượt failureRateThreshold
OPEN → HALF_OPEN: sau waitDurationSeconds
HALF_OPEN → CLOSED: nếu permittedCalls thành công
HALF_OPEN → OPEN:   nếu vẫn còn lỗi
```

**Config quan trọng:**
- failureRateThreshold: % lỗi để mở CB (default: 50%)
- waitDurationSeconds: thời gian OPEN trước khi thử lại (default: 60s)
- slidingWindowSize: số request gần nhất để tính error rate (default: 10)

**Lưu ý:** Decorator này được InventoryStrategyFactory tự động wrap
nếu circuitBreaker.enabled=true trong config. Developer không cần
tự wrap thủ công.

---

### InventoryStrategyFactory *(class)*

**Mô tả:** Factory tạo đúng InventoryStrategy bean dựa trên config
yaml. Developer không cần tự new object hay wire dependency —
factory lo tất cả.

**Logic tạo strategy:**
```
Đọc hcr.inventory.strategy từ config
  → "pessimistic-lock" → tạo PessimisticLockStrategy
  → "optimistic-lock"  → tạo OptimisticLockStrategy
  → "redis-atomic"     → tạo RedisAtomicStrategy

Nếu hcr.inventory.circuit-breaker.enabled=true:
  → Wrap strategy trên bằng CircuitBreakerInventoryDecorator

Developer có thể register custom strategy:
  → factory.registerCustomStrategy("my-strategy", new MyStrategy())
  → config: hcr.inventory.strategy=my-strategy
```

---

### InventoryInitializer *(class)*

**Mô tả:** Chỉ cần thiết cho RedisAtomicStrategy. Khi application
khởi động, load giá trị available từ DB lên Redis để đảm bảo
Redis có dữ liệu đúng trước khi nhận request.

**Khi nào chạy:**
- Application startup (tự động nếu dùng RedisAtomicStrategy)
- Sau khi Reconciliation phát hiện mismatch và fix DB
- Admin yêu cầu reload thủ công

**Các thao tác:**

| Method | Mô tả |
|--------|-------|
| initialize(resourceIds) | Load danh sách resource từ DB lên Redis |
| initialize(resourceId, quantity) | Load 1 resource cụ thể |
| reloadAll() | Load lại tất cả (sau reconciliation) |
| verify(resourceId) | Kiểm tra Redis đồng bộ với DB |

---

### InventoryMetrics *(interface)*

**Mô tả:** Contract để các strategy ghi metrics. Được inject vào
tất cả strategy — mọi thao tác inventory đều được track tự động.

**Metrics được track:**

| Metric | Loại | Mô tả |
|--------|------|-------|
| recordReserveAttempt | Counter | Số lần thử reserve |
| recordReserveSuccess | Counter + Histogram | Thành công + thời gian |
| recordReserveFailure | Counter | Thất bại theo lý do |
| recordReleaseSuccess | Counter | Số lần release |
| recordOversellPrevented | Counter | Số lần chặn được oversell |
| recordLowStock | Counter | Số lần xuống dưới ngưỡng |
| recordDepleted | Counter | Số lần hết hàng |
| updateAvailableGauge | Gauge | Tồn kho realtime |

---

---

# Module 03 — Saga Orchestration

---

## 3.1 Tổng quan

### Vai trò trong hệ thống

Saga Orchestration là **bộ não điều phối** — quyết định thứ tự thực
hiện các bước, xử lý khi có bước fail, và đảm bảo hệ thống luôn
trở về trạng thái nhất quán. Không có Saga, hệ thống không biết
phải làm gì khi thanh toán fail sau khi đã reserve inventory.

### Tại sao cần thiết kế trong framework?

Saga pattern rất dễ implement sai:
- Quên rollback khi một bước fail
- Không handle idempotency (retry tạo ra 2 order)
- Không có timeout → order treo mãi mãi
- Không track trạng thái → không biết đang ở bước nào khi crash

Framework đóng gói toàn bộ flow chuẩn. Developer chỉ cần implement
phần nghiệp vụ cụ thể (tạo order, thanh toán bao nhiêu, làm gì khi
confirm/cancel) — không cần lo về flow orchestration.

### Saga vs 2PC (Two-Phase Commit)

| | Saga (framework dùng) | 2PC |
|--|----------------------|-----|
| Consistency | Eventual | Strong |
| Coupling | Loose | Tight |
| Availability | Cao | Thấp |
| Complexity | Trung bình | Cao |
| Phù hợp | Microservices | Monolith |

### Hai variant của Saga

**Synchronous Saga (P1/P2 style):**
- Trả về kết quả cuối cùng ngay trong HTTP request
- Client biết kết quả ngay: CONFIRMED hoặc CANCELLED
- DB nằm trong critical path → throughput bị giới hạn

**Asynchronous Saga (P3 style):**
- Trả về 202 ACCEPTED + PENDING ngay lập tức
- Kết quả thực tế được xử lý async qua Event Bus
- DB không nằm trong critical path → throughput cao hơn nhiều
- Client cần polling hoặc webhook để biết kết quả cuối

---

## 3.2 Danh sách thành phần

### AbstractSagaOrchestrator *(abstract class — trung tâm module)*

**Mô tả:** Chứa toàn bộ flow chuẩn và state machine của Saga.
Framework đảm bảo flow luôn đúng — developer chỉ implement phần
nghiệp vụ qua các abstract method và lifecycle hooks.

**Methods framework cung cấp sẵn (developer không override):**

| Method | Mô tả |
|--------|-------|
| process(request) | Xử lý một order request từ đầu đến cuối |
| retryPayment(orderId) | Retry thanh toán cho order PENDING |
| adminCancel(orderId, reason) | Admin hủy order thủ công |
| getStatus(orderId) | Lấy trạng thái hiện tại của order |
| processPartial(request) | Xử lý khi không đủ số lượng yêu cầu |
| expireReservation(orderId) | Hủy reservation hết hạn |

**Methods developer bắt buộc implement:**

| Method | Mô tả | Ví dụ implement |
|--------|-------|----------------|
| createOrder(request) | Tạo order object từ request | new ConcertOrder(request) |
| findOrder(orderId) | Tìm order trong DB | concertOrderRepo.findById(orderId) |
| saveOrder(order) | Lưu order vào DB | concertOrderRepo.save(order) |
| buildPaymentRequest(order) | Tạo payment request | new PaymentRequest(order.getAmount()) |
| onConfirmed(order) | Xử lý sau khi confirm | sendConfirmEmail(order) |
| onCancelled(order, reason) | Xử lý sau khi cancel | sendCancelEmail(order, reason) |

**Lifecycle hooks (developer override nếu cần custom behavior):**

| Hook | Khi nào được gọi | Ví dụ dùng |
|------|-----------------|-----------|
| onReserving(order) | Trước khi gọi reserve | Log "Bắt đầu giữ chỗ" |
| onPaymentProcessing(order) | Trước khi gọi charge | Update UI "Đang xử lý TT" |
| onConfirming(order) | Trước khi update CONFIRMED | Gửi SMS trước |
| onCancelling(order) | Trước khi update CANCELLED | Notify warehouse |
| onCompensating(order) | Khi đang rollback | Alert monitoring |
| onExpiring(order) | Khi reservation hết hạn | Release held seats |

**Configuration (developer override để customize):**

| Method | Default | Mô tả |
|--------|---------|-------|
| getReservationTimeoutMinutes() | 5 phút | Sau bao lâu thì expire |
| allowPartialFulfillment() | false | Có chấp nhận đặt một phần không |

---

### SynchronousSagaOrchestrator *(abstract class)*

**Mô tả:** Variant đồng bộ của Saga. Client nhận kết quả cuối cùng
(CONFIRMED hoặc CANCELLED) ngay trong response. Không dùng Event Bus
cho critical path.

**Flow chi tiết:**
```
1. Validate request (AbstractRequestValidator)
2. Idempotency check (IdempotencyHandler)
   → Nếu duplicate: trả về cached result ngay
3. createOrder() → save với status=PENDING
4. inventoryStrategy.reserve()
   → Nếu INSUFFICIENT: cancel order → trả về 409
   → Nếu ERROR: trả về 500
5. paymentGateway.charge() [có timeout]
   → Nếu SUCCESS: tiếp tục
   → Nếu FAILED: release inventory → cancel → trả về 402
   → Nếu TIMEOUT: TimeoutHandler polling...
     → Nếu tìm thấy SUCCESS: tiếp tục
     → Nếu vẫn không rõ: trả về 202 PENDING (Reconciliation lo sau)
6. confirm order → status=CONFIRMED
7. publish OrderConfirmedEvent
8. trả về 201 CONFIRMED
```

---

### AsynchronousSagaOrchestrator *(abstract class)*

**Mô tả:** Variant bất đồng bộ — critical path cực kỳ ngắn, phần
còn lại xử lý qua Event Bus. Thích hợp khi throughput là ưu tiên.

**Critical path (sync — < 10ms):**
```
1. Validate request
2. Idempotency check
3. inventoryStrategy.reserve() [Redis Lua DECR — atomic]
   → Nếu INSUFFICIENT: trả về 409 ngay
4. eventBus.publish(OrderCreatedEvent)
   → Nếu publish fail: release inventory → trả về 500
5. Cache status PENDING lên Redis
6. trả về 202 ACCEPTED
```

**Async path (qua Event Bus):**
```
OrderCreatedEvent
  → OrderCreatedConsumer: INSERT order vào DB
  → PaymentConsumer: charge payment gateway
  → PaymentResultEvent
    → PaymentResultConsumer:
      THÀNH CÔNG: UPDATE order=CONFIRMED, publish OrderConfirmedEvent
      THẤT BẠI:   release inventory, UPDATE order=CANCELLED,
                  publish OrderCancelledEvent
```

---

### SagaStep *(interface)*

**Mô tả:** Đại diện cho một bước trong Saga. Framework compose các
step thành flow hoàn chỉnh. Mỗi step có thể execute và compensate
(hoàn tác).

**Trách nhiệm của mỗi Step:**

| Trách nhiệm | Mô tả |
|-------------|-------|
| execute(context) | Thực thi logic của bước |
| compensate(context) | Hoàn tác — gọi khi bước sau fail |
| getStepName() | Tên bước cho logging/metrics |
| isRetryable() | Có thể retry khi fail tạm thời không |

**Quan hệ execute → compensate:**
```
Step 1: Reserve   → compensate: Release
Step 2: Payment   → compensate: Refund
Step 3: Confirm   → compensate: N/A (final step)

Nếu Step 2 fail:
  → compensate Step 1 (release inventory)
  → KHÔNG compensate Step 3 (chưa chạy)
```

---

### SagaContext *(class)*

**Mô tả:** Object truyền state giữa các step trong cùng một Saga
execution. Đóng vai trò như "hành lý" chứa mọi thông tin cần thiết
để các step giao tiếp với nhau.

**Fields:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| order | AbstractOrder | Order đang được xử lý |
| reservationResult | ReservationResult | Kết quả từ ReservationStep |
| paymentResult | PaymentResult | Kết quả từ PaymentStep |
| completedSteps | List\<String\> | Các step đã hoàn thành |
| failedSteps | List\<String\> | Các step đã fail |
| metadata | Map\<String, Object\> | Developer thêm data tùy ý |

**Tại sao cần SagaContext?**
Nếu không có context, các step phải query DB để lấy thông tin nhau
→ tốn thời gian. Context là in-memory cache trong 1 Saga execution.

---

### ReservationStep / PaymentStep / ConfirmationStep *(classes)*

**Mô tả:** Ba step chuẩn của Saga — framework cung cấp sẵn. Developer
không cần implement các step này.

| Step | Execute | Compensate |
|------|---------|-----------|
| ReservationStep | inventoryStrategy.reserve() | inventoryStrategy.release() |
| PaymentStep | paymentGateway.charge() | paymentGateway.refund() |
| ConfirmationStep | update CONFIRMED + publish event | N/A (final) |

---

### SagaStateRepository *(interface)*

**Mô tả:** Lưu trạng thái Saga để resume nếu crash giữa chừng.
Developer implement với DB của họ.

> ⚠️ **BẮT BUỘC hay OPTIONAL — phụ thuộc vào Saga mode:**
>
> | Saga Mode | SagaStateRepository | Hậu quả nếu thiếu |
> |-----------|--------------------|--------------------|
> | **Synchronous** | Optional | Nếu crash, client retry → tạo Saga mới → OK nhờ idempotency |
> | **Asynchronous** | **BẮT BUỘC** | Crash giữa chừng không biết step nào đã chạy → có thể double charge hoặc double release |
>
> Framework sẽ **throw exception khi startup** nếu `hcr.saga.mode=async`
> mà không có bean implement `SagaStateRepository` trong Spring context.
> Lỗi rõ ràng:
> ```
> HcrFrameworkException: AsynchronousSaga requires a SagaStateRepository
> bean. Please implement SagaStateRepository<YourOrderType> and register
> it as a Spring bean. See documentation for details.
> ```

**Tại sao Async Saga nguy hiểm hơn khi thiếu SagaStateRepository?**

```
Crash scenario trong Async Saga:

Step 1: Redis DECR thành công (inventory đã giảm)
Step 2: Kafka publish OrderCreatedEvent
  → CRASH tại đây
  
Khi restart:
  → Không biết event đã publish chưa
  → Nếu publish lại → PaymentConsumer charge 2 lần
  → Nếu không publish → inventory bị trừ nhưng không có order

SagaStateRepository giải quyết:
  → Lưu trạng thái sau mỗi step
  → Khi restart: biết đã ở step nào → tiếp tục từ đúng chỗ
```

**Methods:**

| Method | Mô tả |
|--------|-------|
| save(context) | Lưu SagaContext sau mỗi step thành công |
| findByOrderId(orderId) | Tìm context để resume sau crash |
| delete(orderId) | Xóa context khi Saga reach terminal state |
| findByStatus(status) | Tìm tất cả Saga đang ở trạng thái nào |

**Gợi ý implement:** Developer có thể implement bằng cách serialize
`SagaContext` thành JSON và lưu vào cùng DB với Order — đơn giản
nhất là thêm column `saga_state` (TEXT/JSONB) vào bảng orders.

---

---

# Module 04 — Payment

---

## 4.1 Tổng quan

### Vai trò trong hệ thống

Payment module abstract hóa việc tích hợp với payment gateway bên
thứ 3. Đây là phần **nguy hiểm nhất** của hệ thống — sai ở đây có
thể dẫn đến: charge tiền 2 lần, mất tiền, hoặc tiền bị trừ mà
không có vé.

### Tại sao cần thiết kế trong framework?

Hai tình huống từ meeting note mà developer thường không xử lý đúng:

**Tình huống A:** Gateway gặp lỗi, không trả về kết quả
```
Order Service gọi charge() → Gateway crash
→ Không có response
→ Developer không biết tiền đã bị trừ chưa
→ Nếu assume FAILED: mất tiền của khách (nếu thực ra đã trừ)
→ Nếu assume SUCCESS: tặng vé miễn phí (nếu thực ra chưa trừ)
```

**Tình huống B:** Gateway thành công nhưng response bị mất
```
Order Service gọi charge() → Gateway xử lý thành công → tiền trừ
→ Response bị mất giữa đường (network partition)
→ Developer nhận timeout → assume FAILED
→ cancel order → rollback inventory
→ Khách mất tiền nhưng không có vé
```

Framework giải quyết cả 2 tình huống bằng TimeoutHandler +
Reconciliation.

---

## 4.2 Danh sách thành phần

### PaymentGateway *(interface)*

**Mô tả:** Contract với payment gateway bên thứ 3. Developer implement
interface này để tích hợp với VNPay, Stripe, MoMo, ZaloPay, v.v.

**Nhóm Core Operations:**

| Method | Mô tả | Ghi chú |
|--------|-------|---------|
| charge(request) | Thực hiện thanh toán | Có idempotency key |
| queryStatus(transactionId) | Query kết quả giao dịch | Giải quyết T/H A và B |
| refund(request) | Hoàn tiền toàn bộ | Compensating action |
| partialRefund(transactionId, amount) | Hoàn một phần tiền | Khi hủy 1 phần đơn |

**Nhóm Pre-Authorization (giữ tiền trước, charge sau):**

| Method | Mô tả | Khi dùng |
|--------|-------|---------|
| preAuthorize(request) | Giữ tiền trong tài khoản | Khách sạn giữ tiền khi check-in |
| capture(authorizationId) | Thực thu tiền đã giữ | Khi check-out |
| voidAuthorization(authorizationId) | Hủy việc giữ tiền | Khi hủy đặt phòng |

**Nhóm Health:**

| Method | Mô tả |
|--------|-------|
| isAvailable() | Gateway có đang hoạt động không |
| getHealth() | Thông tin health chi tiết |
| getGatewayName() | Tên gateway — dùng trong metrics |

---

### AbstractPaymentGateway *(abstract class)*

**Mô tả:** Implement PaymentGateway và đóng gói tất cả logic phức tạp:
idempotency key management, retry, timeout detection, polling.
Developer chỉ cần implement giao tiếp thực tế với từng gateway cụ thể.

**Framework xử lý tự động:**
- Sinh idempotency key từ transactionId → gắn vào request
- Retry khi network error (không phải business error)
- Detect timeout → gọi TimeoutHandler
- Ghi metrics cho mọi call

**Developer chỉ cần implement:**

| Method | Mô tả |
|--------|-------|
| doCharge(request) | Gọi API charge của gateway cụ thể |
| doQuery(transactionId) | Gọi API query của gateway cụ thể |
| doRefund(request) | Gọi API refund của gateway cụ thể |

---

### TimeoutHandler *(class)*

**Mô tả:** Xử lý trường hợp charge() không trả về trong thời gian
quy định. Đây là component giải quyết Tình huống A và B.

**Cơ chế:**
```
charge() timeout sau timeoutMs giây
  → TimeoutHandler.handle(transactionId):
    for attempt = 1..maxPollingAttempts:
      sleep(pollingIntervalMs)
      result = gateway.queryStatus(transactionId)
      
      if result.isSuccess()  → trả về SUCCESS (Tình huống B resolved)
      if result.isFailed()   → trả về FAILED  (Tình huống A resolved)
      if result.isUnknown()  → tiếp tục polling
    
    → Hết attempts: trả về UNKNOWN
      → Saga trả về 202 PENDING
      → Reconciliation sẽ xử lý sau
```

**Config:**
- pollingIntervalMs: khoảng cách giữa các lần poll (default: 5000ms)
- maxPollingAttempts: số lần poll tối đa (default: 6 = 30 giây)

---

### PaymentRequest / PaymentResult *(classes)*

**PaymentRequest — Input:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| transactionId | String | = orderId → idempotency key |
| amount | long | Số tiền (đơn vị: đồng/cent) |
| currency | String | ISO 4217: "VND", "USD" |
| description | String | Mô tả giao dịch |
| metadata | Map | Developer thêm tùy ý |

**PaymentResult — Output:**

| Field | Kiểu | Mô tả |
|-------|------|-------|
| status | PaymentStatus | SUCCESS / FAILED / TIMEOUT / UNKNOWN |
| transactionId | String | ID giao dịch phía chúng ta |
| gatewayTransactionId | String | ID giao dịch phía gateway |
| amount | long | Số tiền thực tế đã xử lý |
| processedAt | Instant | Thời điểm gateway xử lý |
| errorCode | String | Mã lỗi (nếu FAILED) |
| errorMessage | String | Mô tả lỗi |

**PaymentStatus — Ý nghĩa:**

| Status | Ý nghĩa | Hành động |
|--------|---------|-----------|
| SUCCESS | Thanh toán thành công | Confirm order |
| FAILED | Thanh toán thất bại | Cancel order + release inventory |
| TIMEOUT | Gateway không phản hồi | TimeoutHandler polling |
| UNKNOWN | Không rõ kết quả | Trả về PENDING, Reconciliation xử lý |

---

### MockPaymentGateway *(class)*

**Mô tả:** Implementation giả lập — dùng cho testing và benchmark.
Có thể configure để simulate mọi tình huống thực tế.

**Configurable behaviors:**

| Config | Mô tả | Default |
|--------|-------|---------|
| successRate | Tỉ lệ thành công | 80% |
| simulatedDelayMs | Delay giả lập | 100ms |
| timeoutRate | Tỉ lệ timeout | 5% |
| noResponseRate | Tỉ lệ không trả lời (T/H A) | 2% |
| lateSuccessRate | Tỉ lệ thành công muộn (T/H B) | 3% |

---

### GatewayHealth *(class)*

**Mô tả:** Trạng thái health của payment gateway tại thời điểm query.
Dùng để quyết định có gọi gateway không (tránh gọi khi gateway down).

| Field | Kiểu | Mô tả |
|-------|------|-------|
| status | HealthStatus | UP / DEGRADED / DOWN |
| successRateLast5Min | double | Tỉ lệ thành công 5 phút gần nhất |
| avgLatencyMs | double | Latency trung bình |
| activeConnections | int | Số connection đang mở |
| checkedAt | Instant | Thời điểm kiểm tra |

---

---

# Module 05 — Event Bus

---

## 5.1 Tổng quan

### Vai trò trong hệ thống

Event Bus là **hệ thống thần kinh** của framework — truyền thông tin
giữa các component mà không cần chúng biết nhau trực tiếp. Đặc biệt
quan trọng cho AsynchronousSaga — mọi bước async đều đi qua Event Bus.

### Tại sao cần abstract hóa?

Nếu hardcode Kafka, framework chỉ dùng được với Kafka. Nhưng:
- Hệ thống đã có RabbitMQ → không thể dùng framework
- Hệ thống nhỏ không muốn vận hành Kafka → bị loại
- Testing cần spin up Kafka thật → chậm và phức tạp

Giải pháp: Abstract hóa qua EventBus interface + 4 adapter.
Developer chỉ config loại adapter — code không thay đổi.

### Delivery guarantee

Framework cam kết **at-least-once delivery** cho tất cả adapter:
- Message sẽ được deliver ít nhất 1 lần
- Có thể được deliver nhiều hơn 1 lần (nếu crash trước khi ack)
- Developer phải implement idempotent consumer để xử lý duplicate

### Sự khác nhau giữa 4 adapter

| | Kafka | RabbitMQ | Redis Streams | InMemory |
|--|-------|----------|---------------|---------|
| Retention | Lâu dài | Xóa sau consume | Configurable | Không có |
| Ordering | Trong partition | Trong queue | Trong stream | FIFO |
| Replay | Có (seek offset) | Không | Có (XRANGE) | Không |
| Infrastructure | Nặng | Trung bình | Nhẹ (dùng Redis) | Không cần |
| Idempotent produce | Native | Qua Redis | Qua Redis | Built-in |
| Phù hợp | Production high-load | Production general | Khi đã có Redis | Testing |

> ⚠️ **Cảnh báo quan trọng — Semantic differences:**
>
> Framework abstract hóa 4 adapter để đơn giản hóa việc switch.
> Tuy nhiên, **semantics khác nhau đáng kể** — developer phải hiểu
> rõ trước khi chọn.
>
> **Nguy hiểm phổ biến nhất:** Test với `InMemoryEventBusAdapter`
> (synchronous, exactly-once) rồi deploy `KafkaEventBusAdapter`
> (asynchronous, at-least-once) → behavior hoàn toàn khác nhau ở
> production → bug khó phát hiện.
>
> **Nguyên tắc:** Môi trường integration test nên dùng cùng loại
> adapter với production. `InMemoryEventBusAdapter` chỉ dành cho
> unit test thuần túy.

---

## 5.2 Danh sách thành phần

### EventBus *(interface)*

**Mô tả:** Contract trung tâm với các hành động cơ bản, không phụ
thuộc implementation cụ thể nào.

**Methods:**

| Method | Mô tả | Ghi chú |
|--------|-------|---------|
| publish(event) | Publish event lên default destination | Auto-detect destination từ event type |
| publish(event, destination) | Publish lên destination cụ thể | Override default routing |
| publishIdempotent(event, key) | Publish có idempotency | Không publish 2 lần cùng key |
| publishBatch(events) | Publish nhiều event cùng lúc | Hiệu quả hơn publish từng cái |
| subscribe(eventType, handler) | Đăng ký nhận event | Dynamic subscription |
| unsubscribe(eventType, handler) | Hủy đăng ký | |
| getCapabilities() | Lấy capabilities của adapter đang dùng | Dùng để conditional logic |

---

### EventBusCapabilities *(class)*

**Mô tả:** Khai báo tường minh những tính năng mà mỗi adapter hỗ
trợ. Framework dùng để warn developer khi họ rely vào tính năng
không được support bởi adapter đang config.

**Mục đích thiết kế:**
Thay vì để developer phát hiện sự khác biệt qua bug ở production,
framework expose capabilities một cách minh bạch — developer có thể
query và framework tự động cảnh báo khi có mismatch.

**Danh sách capabilities và support theo adapter:**

| Capability | Mô tả | Kafka | RabbitMQ | Redis Streams | InMemory |
|------------|-------|:-----:|:--------:|:-------------:|:--------:|
| supportsOrdering | Đảm bảo thứ tự message | ✓ (per partition) | ✓ (per queue) | ✓ (per stream) | ✓ |
| supportsReplay | Đọc lại message cũ | ✓ | ✗ | ✓ | ✗ |
| supportsExactlyOnce | Xử lý đúng 1 lần | ✓ (idempotent producer) | ✗ | ✗ | ✓ |
| supportsPartitioning | Partition message theo key | ✓ | ✗ | ✗ | ✗ |
| isSynchronous | Deliver trong cùng thread | ✗ | ✗ | ✗ | ✓ |
| supportsDLQ | Dead Letter Queue | ✓ | ✓ | Hạn chế | ✓ (in-memory) |
| supportsMultiConsumer | Nhiều consumer song song | ✓ | ✓ | ✓ | ✗ |

**Behavior của framework khi capability không được support:**

Framework log **WARNING** (không throw exception, không block startup)
khi phát hiện developer rely vào capability không được support:

```
[HCR-WARN] Capability mismatch detected:
  Adapter: RabbitMQEventBusAdapter
  Required: supportsReplay = true
  Actual:   supportsReplay = false
  Context:  EventHandler 'OrderEventReplayHandler' subscribes to past events.
  Advice:   Switch to KafkaEventBusAdapter or RedisStreamEventBusAdapter
            if replay is required, or remove replay dependency.
```

**Cách developer sử dụng:**
Developer có thể query capabilities tại runtime thông qua
`eventBus.getCapabilities()` để viết conditional logic mà không
hardcode tên adapter — đảm bảo code portable giữa các adapter.

---

---

### EventDestination *(class)*

**Mô tả:** Abstraction cho topic/queue/exchange — không dùng tên
cụ thể của từng message queue. Framework map EventDestination sang
cấu trúc phù hợp với từng adapter.

**Mapping:**

| EventDestination.name | Kafka | RabbitMQ | Redis Streams |
|----------------------|-------|----------|---------------|
| "order-created" | Topic: "hcr.order-created" | Routing Key: "order-created" | Stream: "hcr:stream:order-created" |

**Factory methods:**

| Method | Mô tả |
|--------|-------|
| of(name) | Tạo destination với tên cụ thể |
| forEventType(eventClass) | Tạo destination từ tên class event |

---

### EventHandler *(interface)*

**Mô tả:** Developer implement để xử lý event. Có Acknowledgment
để báo framework đã xử lý xong hay chưa.

**Methods:**

| Method | Mô tả |
|--------|-------|
| handle(event, ack) | Xử lý event và gọi ack.acknowledge() khi xong |
| onDeadLetter(event, cause) | Gọi khi event fail quá nhiều lần |

**Nguyên tắc implement:**
- Implement phải idempotent: xử lý cùng 1 event 2 lần cho kết quả giống nhau
- Gọi ack.acknowledge() SAU KHI đã xử lý xong (không phải trước)
- Gọi ack.reject() nếu cần retry, ack.reject(false) nếu muốn dead letter

---

### Acknowledgment *(interface)*

**Mô tả:** Abstraction cho việc xác nhận — thay thế cho
Kafka-specific Acknowledgment. Mọi adapter đều implement interface
này theo cách riêng.

| Method | Kafka | RabbitMQ | Redis Streams | InMemory |
|--------|-------|----------|---------------|---------|
| acknowledge() | commitSync() | basicAck() | XACK | no-op |
| reject() | seek to offset | basicNack(requeue=true) | không XACK | retry |
| reject(false) | seek to offset | basicNack(requeue=false) | XACK + dead letter | dead letter |

---

### KafkaEventBusAdapter *(class)*

**Mô tả:** Adapter cho Apache Kafka — default implementation.
Tận dụng tính năng native của Kafka cho performance tốt nhất.

**Config được framework set sẵn (at-least-once):**
- acks=all: đảm bảo không mất message
- retries=3: retry khi network error
- enable.idempotence=true: không duplicate khi producer retry
- ack-mode=manual_immediate: consumer chỉ ack sau khi xử lý xong

**publishIdempotent():** Dùng Kafka idempotent producer — không cần
Redis check thêm.

**Routing:** EventDestination.name → "hcr.{name}" topic name
(prefix configurable)

---

### RabbitMQEventBusAdapter *(class)*

**Mô tả:** Adapter cho RabbitMQ. Dùng Topic Exchange để routing
linh hoạt.

**Topology:**
```
Exchange: hcr.events (type: topic)
  ├── Binding: routing-key="order-created" → Queue: hcr.order-created.queue
  ├── Binding: routing-key="payment-*"    → Queue: hcr.payment.queue
  └── ...
```

**publishIdempotent():** RabbitMQ không có native idempotent producer
→ framework check eventId trong Redis trước khi publish. Nếu eventId
đã tồn tại trong Redis → skip.

**Ack mode:** manual — consumer xác nhận sau khi xử lý xong.

---

### RedisStreamEventBusAdapter *(class)*

**Mô tả:** Adapter dùng Redis Streams (XADD/XREADGROUP). Phù hợp
khi đã có Redis infrastructure, không muốn thêm Kafka hoặc RabbitMQ.

**Cơ chế:**
```
publish():
  XADD hcr:stream:{destination} * eventId {uuid} payload {json}

subscribe():
  XREADGROUP GROUP hcr-consumers consumer-{n}
  COUNT 10 BLOCK 2000
  STREAMS hcr:stream:{destination} >

acknowledge():
  XACK hcr:stream:{destination} hcr-consumers {messageId}
```

**publishIdempotent():** Check eventId trong Redis SET trước XADD.

**Consumer group:** Framework tự tạo consumer group nếu chưa tồn
tại (XGROUP CREATE ... MKSTREAM).

---

### InMemoryEventBusAdapter *(class)*

**Mô tả:** Adapter hoàn toàn in-memory, không cần infrastructure.
Chỉ dùng cho testing — không phải production.

**Đặc điểm:**
- Synchronous delivery: handler được gọi ngay trong thread của publisher
- Không có persistence: restart mất toàn bộ events
- Thread-safe: dùng ConcurrentHashMap

**Testing utilities đặc biệt:**
- getPublishedEvents(): lấy tất cả events đã publish
- getPublishedEvents(type): lấy events theo loại
- clearEvents(): xóa tất cả (giữa các test case)
- getPublishedCount(type): đếm số events theo loại

---

### Domain Events *(framework cung cấp sẵn)*

**Mô tả:** Tất cả event chuẩn mà framework publish. Developer subscribe
để xử lý. Tất cả đều extend DomainEvent.

**Nhóm Inventory Events:**

| Event | Khi nào publish | Ai publish | Ai thường subscribe |
|-------|----------------|------------|---------------------|
| ResourceReservedEvent | Reserve thành công | InventoryStrategy | OrderCreatedConsumer (persist DB) |
| ResourceReleasedEvent | Release thành công | InventoryStrategy | LoggingConsumer |
| ResourceDepletedEvent | Hết hàng | InventoryStrategy | NotificationService |
| ResourceLowStockEvent | Dưới ngưỡng | InventoryStrategy | AlertService |
| ResourceRestockedEvent | Thêm tồn kho | InventoryStrategy | — |

**Nhóm Order Events:**

| Event | Khi nào publish | Ai publish | Ai thường subscribe |
|-------|----------------|------------|---------------------|
| OrderCreatedEvent | Order vừa tạo (async) | AsyncSagaOrchestrator | PaymentConsumer |
| OrderConfirmedEvent | Order hoàn thành | ConfirmationStep | EmailService, AnalyticsService |
| OrderCancelledEvent | Order bị hủy | SagaOrchestrator | EmailService, InventoryService (release) |
| OrderExpiredEvent | Giữ chỗ hết hạn | ReconciliationService | EmailService, InventoryService |

**Nhóm Payment Events:**

| Event | Khi nào publish | Ai publish | Ai thường subscribe |
|-------|----------------|------------|---------------------|
| PaymentSucceededEvent | Thanh toán thành công | PaymentStep | ConfirmationConsumer |
| PaymentFailedEvent | Thanh toán thất bại | PaymentStep | CancellationConsumer |
| PaymentTimeoutEvent | Không phản hồi | TimeoutHandler | ReconciliationService |
| PaymentUnknownEvent | Không rõ kết quả | TimeoutHandler | ReconciliationService |

**Nhóm Reconciliation Events:**

| Event | Khi nào publish | Ai publish |
|-------|----------------|------------|
| ReconciliationStartedEvent | Bắt đầu cycle | ReconciliationService |
| ReconciliationFixedEvent | Fix xong 1 case | ReconciliationService |
| InventoryMismatchEvent | Phát hiện lệch Redis vs DB | InventoryReconciler |

---

---

# Module 06 — Gateway

---

## 6.1 Tổng quan

### Vai trò trong hệ thống

Gateway là **tầng bảo vệ đầu tiên** — mọi request đều phải đi qua
đây trước khi được xử lý. Gateway đảm nhiệm 4 trách nhiệm:
1. **Validate:** Request có hợp lệ không?
2. **Idempotency:** Request này đã xử lý chưa?
3. **Rate Limit:** User có gửi quá nhiều request không?
4. **Circuit Breaker:** Hệ thống có đang bị quá tải không?

### Tại sao cần thiết kế trong framework?

Nếu mỗi use case tự implement 4 trách nhiệm trên, code sẽ bị lặp
lại và dễ thiếu sót. Developer thường chỉ nhớ implement validate,
quên idempotency → duplicate order khi client retry.

Framework đóng gói tất cả vào pipeline chuẩn — developer chỉ cần
implement business validation.

---

## 6.2 Danh sách thành phần

### FrameworkGateway *(abstract class)*

**Mô tả:** Entry point duy nhất vào hệ thống. Framework đảm bảo
mọi request đều đi qua pipeline đầy đủ.

**Pipeline (theo thứ tự):**
```
1. Validate (AbstractRequestValidator):
   → Basic field validation (framework)
   → Business validation (developer)
   → Nếu fail: trả về 400 ngay

2. Idempotency check (IdempotencyHandler):
   → Kiểm tra idempotencyKey đã xử lý chưa
   → Nếu đã xử lý: trả về cached result (200/201)
   → Nếu chưa: tiếp tục

3. Rate Limit (RateLimiter):
   → Kiểm tra user/resource có vượt limit không
   → Nếu vượt: trả về 429 Too Many Requests
   → Nếu không: tiếp tục

4. Circuit Breaker check:
   → Kiểm tra CB state
   → Nếu OPEN: trả về 503 Service Unavailable ngay
   → Nếu CLOSED/HALF_OPEN: tiếp tục

5. Submit to SagaOrchestrator:
   → Gọi orchestrator.process(request)
   → Nhận kết quả
   → Mark idempotency processed
   → Trả về response
```

**Methods developer implement:**

| Method | Mô tả |
|--------|-------|
| validateBusinessRules(request) | Business-specific validation |

**Methods developer override để customize:**

| Method | Default | Có thể override |
|--------|---------|----------------|
| shouldRateLimit(request) | true | Disable rate limit cho admin |
| getRateLimitKey(request) | requesterId | Dùng IP hoặc custom key |
| getIdempotencyKey(request) | idempotencyKey | Custom key generation |

---

### RateLimiter *(interface)*

**Mô tả:** Contract cho rate limiting. Framework cung cấp
RedisTokenBucketRateLimiter, developer có thể implement custom.

**Methods:**

| Method | Mô tả | Return |
|--------|-------|--------|
| tryAcquire(key) | Thử lấy 1 permit | boolean |
| tryAcquire(key, permits) | Thử lấy n permits | boolean |
| tryAcquireWithInfo(key) | Thử lấy + lấy thông tin | RateLimitResult |
| configure(key, rps, burst) | Cấu hình limit cho key | void |

---

### RedisTokenBucketRateLimiter *(class)*

**Mô tả:** Token Bucket algorithm trên Redis. Thread-safe nhờ Lua
script atomic.

**Token Bucket hoạt động:**
```
Mỗi "bucket" (per key) có:
  - capacity: số token tối đa (= burstCapacity)
  - refillRate: số token thêm mỗi giây (= permitsPerSecond)

Mỗi request tiêu 1 token:
  → Nếu còn token: cho qua + trừ 1 token
  → Nếu hết token: block request (429)

Bucket được refill tự động theo thời gian thực.
```

**Config:**
- permitsPerSecond: số request cho phép mỗi giây
- burstCapacity: số request tối đa có thể xử lý cùng lúc (burst)
- Config per-resource và per-user độc lập nhau

---

### IdempotencyHandler *(interface)*

**Mô tả:** Đảm bảo cùng một request không bị xử lý 2 lần —
quan trọng khi client retry sau timeout.

**Methods:**

| Method | Mô tả |
|--------|-------|
| isDuplicate(key) | Key này đã xử lý chưa? |
| markProcessed(key, result) | Đánh dấu đã xử lý + cache result |
| getCachedResult(key) | Lấy kết quả đã cache |
| expire(key) | Xóa cache thủ công |

---

### RedisIdempotencyHandler *(class)*

**Mô tả:** Implement IdempotencyHandler dùng Redis với TTL.

**Cơ chế:**
```
markProcessed(key, result):
  SET "hcr:idempotency:{key}" {serialized result}
  EXPIRE "hcr:idempotency:{key}" ttlSeconds

isDuplicate(key):
  EXISTS "hcr:idempotency:{key}"
  → true: đã xử lý → lấy cached result trả về ngay
  → false: chưa xử lý → tiếp tục

TTL default: 86400 giây (24 giờ)
```

---

### AbstractRequestValidator *(abstract class)*

**Mô tả:** Validate request theo 2 tầng — framework lo basic
validation, developer lo business validation.

**Tầng 1 — Framework lo (không cần developer làm gì):**
- Null check tất cả required fields
- Format validation (UUID format, positive numbers...)
- Constraint validation (quantity > 0, resourceId không empty...)

**Tầng 2 — Developer implement:**
- Business rule validation
- Ví dụ: ticketCategory có hợp lệ không? user có đủ tuổi không?
  concert đã bắt đầu chưa? user đã đặt quá maxPerUser chưa?

**Flow:**
```
validate(request):
  basic = validateBasicFields(request)    // framework
  business = validateBusinessRules(request) // developer
  return basic.merge(business)
```

---

### RateLimitResult *(class)*

**Mô tả:** Kết quả chi tiết của rate limit check — cho phép developer
hiển thị thông tin hữu ích cho client.

| Field | Kiểu | Mô tả |
|-------|------|-------|
| allowed | boolean | Có được phép không |
| remainingPermits | long | Còn bao nhiêu permit trong window |
| resetAfterMs | long | Bao lâu nữa thì reset |
| limitPerSecond | long | Giới hạn đã config |

---

---

# Module 07 — Reconciliation

---

## 7.1 Tổng quan

### Vai trò trong hệ thống

Reconciliation là **safety net** — chạy ngầm định kỳ, phát hiện và
sửa các inconsistency mà real-time flow không xử lý được. Trong
distributed system, crash có thể xảy ra ở bất kỳ đâu, bất kỳ lúc
nào — Reconciliation đảm bảo hệ thống luôn trở về trạng thái đúng,
dù mất thêm chút thời gian.

### Tại sao cần thiết kế trong framework?

Developer thường bỏ quên Reconciliation vì:
- Khó test (cần simulate crash)
- Không thấy được bằng mắt thường
- "Để sau" rồi không bao giờ làm

Nhưng trong production, thiếu Reconciliation dẫn đến:
- Order treo PENDING mãi mãi
- Tồn kho Redis lệch với DB (oversell sau crash)
- Tiền bị trừ nhưng không có vé

Framework đóng gói sẵn logic phát hiện 5 loại inconsistency phổ
biến nhất — developer chỉ cần implement cách xử lý theo nghiệp vụ.

---

## 7.2 Năm loại inconsistency

### Case 1: Stale Pending Order

**Mô tả:** Order ở trạng thái PENDING quá lâu (vượt timeout).
Có thể do payment gateway không phản hồi.

**Nguyên nhân:**
- Tình huống A: gateway lỗi, không trả về kết quả
- Tình huống B: gateway thành công nhưng response bị mất
- Consumer crash trước khi xử lý

**Cách phát hiện:** Scan DB tìm order có status=PENDING và
updatedAt < (now - timeout).

**Cách xử lý:**
- Gọi paymentGateway.queryStatus() để verify
- Nếu SUCCESS (Tình huống B): handleLatePaymentSuccess() — confirm order
- Nếu FAILED hoặc không tồn tại (Tình huống A): handleTimeout() — cancel + release

---

### Case 2: Late Payment Success

**Mô tả:** Order CANCELLED (do timeout) nhưng thực ra payment đã
thành công — tiền đã bị trừ.

**Đây là case nguy hiểm nhất — khách mất tiền.**

**Cách xử lý developer implement:**
- Reconfirm order nếu còn inventory
- Hoặc refund ngay lập tức
- Gửi email xin lỗi và thông báo rõ kết quả

---

### Case 3: Inventory Mismatch

**Mô tả:** Giá trị available trong Redis khác với DB.

**Nguyên nhân:**
- Redis crash → AOF restore không đầy đủ (mất < 1 giây data)
- Consumer bị kill trước khi persist xuống DB
- Bug trong consumer logic

**Cách phát hiện:** So sánh InventorySnapshot từ Redis với DB.

**Cách xử lý:**
- Nếu delta nhỏ (trong ngưỡng): tự động fix Redis theo DB
- Nếu delta lớn: alert + chờ manual review

---

### Case 4: Unpersisted Reservation

**Mô tả:** Order đã CONFIRMED trong Redis cache nhưng DB chưa
được cập nhật (consumer lag cao hoặc consumer crash).

**Cách phát hiện:** Tìm order CONFIRMED trong Redis cache nhưng
DB vẫn còn PENDING.

**Cách xử lý:** Replay event hoặc force update DB theo Redis state.

---

### Case 5: Duplicate Order

**Mô tả:** Tồn tại 2 order với cùng idempotencyKey — idempotency
layer bị bypass hoặc có bug.

**Cách phát hiện:** Query DB tìm idempotencyKey xuất hiện nhiều hơn 1 lần.

**Cách xử lý:** Giữ 1 order (ưu tiên CONFIRMED), cancel các order còn lại.

---

## 7.3 Danh sách thành phần

### AbstractReconciliationService *(abstract class — trung tâm module)*

**Mô tả:** Framework tự chạy định kỳ, tự phát hiện 5 loại case,
gọi đúng handler. Developer implement handler theo nghiệp vụ.

**Framework tự làm:**
- Chạy theo schedule (fixedDelay configurable)
- Distributed lock: đảm bảo chỉ 1 instance chạy tại 1 thời điểm
  (quan trọng khi deploy nhiều instance)
- Phát hiện 5 loại inconsistency
- Ghi metrics sau mỗi lần chạy
- Publish ReconciliationStartedEvent và ReconciliationFixedEvent

**Developer bắt buộc implement:**

| Method | Mô tả |
|--------|-------|
| findStalePendingOrders(timeoutMinutes) | Query DB lấy order PENDING quá lâu |
| handleTimeout(order) | Xử lý order timeout (Tình huống A) |
| handleLatePaymentSuccess(order, result) | Xử lý khi tìm thấy payment muộn (T/H B) |
| handleInventoryMismatch(resourceId, redis, db) | Xử lý lệch inventory |
| findUnpersistedReservations() | Tìm reservation chưa persist |
| handleUnpersistedReservation(order) | Fix reservation chưa persist |
| findDuplicateOrders() | Tìm duplicate order |
| handleDuplicateOrders(duplicates) | Xử lý duplicate |

**Developer override để customize:**

| Method | Default |
|--------|---------|
| getTimeoutMinutes() | 5 phút |
| getScheduleDelayMs() | 300,000ms (5 phút) |
| getInventoryMismatchThreshold() | 0 (bất kỳ lệch nào cũng alert) |

---

### InventoryReconciler *(class)*

**Mô tả:** Chuyên xử lý Case 3 — so sánh Redis vs DB và fix
mismatch. Được AbstractReconciliationService gọi tự động.

**Methods:**

| Method | Mô tả |
|--------|-------|
| reconcile(resourceId) | Reconcile 1 resource |
| reconcileAll(resourceIds) | Reconcile nhiều resource |
| compare(resourceId) | So sánh Redis vs DB, trả về delta |
| autoFix(resourceId, delta) | Tự fix nếu delta <= threshold |

---

### OrderReconciler *(class)*

**Mô tả:** Chuyên xử lý Case 1 và 2 — query payment gateway để
verify kết quả của stale pending orders.

**Methods:**

| Method | Mô tả |
|--------|-------|
| reconcile(staleOrders) | Reconcile danh sách stale orders |
| verify(order) | Verify 1 order với payment gateway |

---

### ReconciliationResult *(class)*

**Mô tả:** Tổng kết sau mỗi lần chạy reconciliation cycle.

| Field | Kiểu | Mô tả |
|-------|------|-------|
| totalScanned | int | Tổng số record đã scan |
| totalFixed | int | Số inconsistency đã fix |
| totalFailed | int | Số case không fix được |
| fixedByCase | Map\<ReconciliationCase, Integer\> | Fix theo từng loại |
| errors | List\<String\> | Chi tiết lỗi |
| duration | Duration | Thời gian chạy |
| runAt | Instant | Thời điểm chạy |

---

### ReconciliationCase *(enum)*

| Case | Mô tả |
|------|-------|
| STALE_PENDING | Order PENDING quá lâu |
| LATE_PAYMENT_SUCCESS | Thanh toán thành công muộn |
| INVENTORY_MISMATCH | Redis lệch với DB |
| UNPERSISTED_RESERVATION | Order confirm chưa persist |
| DUPLICATE_ORDER | 2 order cùng idempotency key |

---

---

# Module 08 — Observability

---

## 8.1 Tổng quan

### Vai trò trong hệ thống

Observability cung cấp visibility — developer có thể nhìn vào bên
trong framework và biết chính xác chuyện gì đang xảy ra: bao nhiêu
request đang xử lý, inventory còn bao nhiêu realtime, bao nhiêu
lần thanh toán timeout, reconciliation đang fix gì...

### Tại sao cần là first-class citizen?

Nếu framework không expose metrics sẵn:
- Developer phải tự viết metrics cho từng operation
- Dễ thiếu sót (quên đo oversell, quên đo payment timeout...)
- Không có Grafana dashboard chuẩn → mỗi người tự vẽ khác nhau

Framework expose metrics tự động cho tất cả operation quan trọng.
Developer chỉ cần Prometheus + Grafana là có dashboard đầy đủ.

---

## 8.2 Danh sách thành phần

### FrameworkMetrics *(interface)*

**Mô tả:** Contract định nghĩa tất cả metrics mà framework track.
Được inject vào mọi module — không module nào ghi metrics trực tiếp
ra Prometheus mà phải qua interface này.

**Nhóm Inventory Metrics:**

| Metric Name | Loại | Tags | Mô tả |
|-------------|------|------|-------|
| hcr_reservation_attempts_total | Counter | resourceId, strategy | Tổng số lần reserve |
| hcr_reservation_success_total | Counter | resourceId | Thành công |
| hcr_reservation_failure_total | Counter | resourceId, reason | Thất bại theo lý do |
| hcr_reservation_duration_ms | Histogram | resourceId, strategy | Thời gian reserve |
| hcr_release_total | Counter | resourceId | Số lần release |
| hcr_oversell_prevented_total | Counter | resourceId | Số lần chặn oversell |
| hcr_inventory_available | Gauge | resourceId | Tồn kho realtime |
| hcr_low_stock_events_total | Counter | resourceId | Số lần xuống dưới ngưỡng |
| hcr_depleted_events_total | Counter | resourceId | Số lần hết hàng |

**Nhóm Saga Metrics:**

| Metric Name | Loại | Tags | Mô tả |
|-------------|------|------|-------|
| hcr_saga_started_total | Counter | resourceId, type | Số Saga bắt đầu |
| hcr_saga_confirmed_total | Counter | resourceId | Kết thúc thành công |
| hcr_saga_cancelled_total | Counter | resourceId, reason | Bị hủy theo lý do |
| hcr_saga_compensated_total | Counter | resourceId | Phải rollback |
| hcr_saga_duration_ms | Histogram | resourceId, outcome | Thời gian xử lý |

**Nhóm Payment Metrics:**

| Metric Name | Loại | Tags | Mô tả |
|-------------|------|------|-------|
| hcr_payment_attempts_total | Counter | gateway | Tổng số lần gọi |
| hcr_payment_success_total | Counter | gateway | Thành công |
| hcr_payment_failure_total | Counter | gateway, errorCode | Thất bại |
| hcr_payment_timeout_total | Counter | gateway | Timeout |
| hcr_payment_unknown_total | Counter | gateway | Không rõ kết quả |
| hcr_payment_duration_ms | Histogram | gateway, status | Thời gian xử lý |

**Nhóm Reconciliation Metrics:**

| Metric Name | Loại | Tags | Mô tả |
|-------------|------|------|-------|
| hcr_reconciliation_run_total | Counter | — | Số lần chạy |
| hcr_reconciliation_fixed_total | Counter | case | Số case đã fix |
| hcr_reconciliation_failed_total | Counter | case | Số case không fix được |
| hcr_reconciliation_duration_ms | Histogram | — | Thời gian mỗi lần chạy |
| hcr_inventory_mismatch_total | Counter | resourceId | Số lần phát hiện lệch |
| hcr_inventory_mismatch_delta | Histogram | resourceId | Độ lệch phát hiện |

**Nhóm Event Bus Metrics:**

| Metric Name | Loại | Tags | Mô tả |
|-------------|------|------|-------|
| hcr_event_published_total | Counter | eventType, adapter | Số event publish |
| hcr_event_consumed_total | Counter | eventType | Số event consume |
| hcr_event_failed_total | Counter | eventType, reason | Consume thất bại |
| hcr_event_consume_duration_ms | Histogram | eventType | Thời gian xử lý event |

**Nhóm Gateway Metrics:**

| Metric Name | Loại | Tags | Mô tả |
|-------------|------|------|-------|
| hcr_gateway_requests_total | Counter | resourceId, result | Tổng request vào gateway |
| hcr_rate_limit_rejected_total | Counter | key | Bị rate limit |
| hcr_idempotency_hit_total | Counter | — | Duplicate request được cache |
| hcr_circuit_breaker_state | Gauge | resourceId | State CB (0=CLOSED, 1=OPEN, 2=HALF_OPEN) |

---

### MicrometerFrameworkMetrics *(class)*

**Mô tả:** Implement FrameworkMetrics dùng Micrometer — tự động
export sang Prometheus. Developer không cần config gì thêm ngoài
việc có Prometheus endpoint.

**Đặc điểm:**
- Auto-register khi Micrometer + Prometheus có trong classpath
- Tất cả metric đều có prefix "hcr_"
- Tags chuẩn hóa theo Open Telemetry convention
- Histogram buckets được pre-configured phù hợp với latency web

---

### GrafanaDashboardTemplate *(resource file)*

**Mô tả:** File JSON Grafana dashboard đi kèm framework. Developer
import vào Grafana là có dashboard đầy đủ ngay — không cần tự config.

**Các panel trong dashboard:**

| Panel | Loại | Metric dùng |
|-------|------|------------|
| Throughput (req/s) | Time series | hcr_saga_started_total |
| P50/P95/P99 Latency | Time series | hcr_saga_duration_ms |
| Inventory Available | Gauge | hcr_inventory_available |
| Oversell Prevented | Stat | hcr_oversell_prevented_total |
| Payment Success Rate | Gauge | hcr_payment_success/attempts |
| Payment Timeout Rate | Time series | hcr_payment_timeout_total |
| Saga Outcome | Pie chart | confirmed/cancelled/compensated |
| Reconciliation Fixed | Bar chart | hcr_reconciliation_fixed_total by case |
| Circuit Breaker State | State timeline | hcr_circuit_breaker_state |
| Event Bus Health | Time series | hcr_event_published/consumed/failed |

---

---

# Module 09 — Testing Support

---

## 9.1 Tổng quan

### Vai trò trong hệ thống

Testing Support cung cấp tools để developer test use case của họ
một cách dễ dàng — không cần infrastructure thật (Kafka, Redis, DB)
khi viết unit test.

### Tại sao cần thiết kế trong framework?

Nếu không có Testing Support:
- Test cần spin up Kafka, Redis, PostgreSQL → chậm (30+ giây)
- CI/CD phức tạp và brittle
- Developer ngại viết test → chất lượng giảm

Với Testing Support:
- Unit test chạy trong < 1 giây
- Không cần Docker trong unit test
- Có sẵn tools để test concurrency và correctness

---

## 9.2 Danh sách thành phần

### FrameworkTestSupport *(utility class)*

**Mô tả:** Tập hợp helper methods cho mọi loại test liên quan đến
framework.

**Nhóm Factory Methods:**

| Method | Mô tả |
|--------|-------|
| inMemoryInventory(initialStock) | Tạo InMemoryInventoryStrategy |
| mockPayment(successRate) | Tạo MockPaymentGateway |
| inMemoryEventBus() | Tạo InMemoryEventBusAdapter |

**Nhóm Concurrency Testing:**

| Method | Mô tả |
|--------|-------|
| simulateConcurrentRequests(orchestrator, request, users, total) | Giả lập N users đồng thời gửi requests |

**Nhóm Assertions:**

| Method | Mô tả |
|--------|-------|
| assertNoOversell(resourceId, strategy) | Đảm bảo available >= 0 |
| assertZeroOversell(result) | Đảm bảo ConcurrencyTestResult có 0 oversell |
| assertThroughputAbove(result, minTps) | Đảm bảo throughput đạt ngưỡng |
| assertEventPublished(bus, eventType) | Đảm bảo event đã được publish |
| assertEventualConsistency(orderId, repo, timeout) | Đảm bảo order reach terminal state |

---

### InMemoryInventoryStrategy *(class)*

**Mô tả:** Implement InventoryStrategy hoàn toàn trong memory.
Thread-safe (dùng AtomicLong). Không cần Redis hay DB.

**Testing utilities:**

| Method | Mô tả |
|--------|-------|
| getCurrentAvailable(resourceId) | Tồn kho hiện tại |
| getReserveCallCount(resourceId) | Số lần reserve đã gọi |
| getOversellAttemptCount(resourceId) | Số lần cố tình oversell |
| reset(resourceId, quantity) | Reset tồn kho giữa các test |

**Đảm bảo zero-oversell:** AtomicLong.compareAndSet đảm bảo không
có race condition — giá trị không bao giờ âm.

---

### ConcurrencyTestResult *(class)*

**Mô tả:** Kết quả của simulateConcurrentRequests().
Chứa đầy đủ thông tin để verify correctness và performance.

| Field | Kiểu | Mô tả |
|-------|------|-------|
| totalRequests | int | Tổng số request gửi |
| successCount | int | Số thành công |
| failureCount | int | Số thất bại |
| oversellCount | int | Số lần oversell (phải luôn = 0) |
| throughputTps | long | Throughput đạt được |
| p50LatencyMs | long | P50 latency |
| p95LatencyMs | long | P95 latency |
| p99LatencyMs | long | P99 latency |
| totalDuration | Duration | Tổng thời gian chạy |
| errors | List\<String\> | Chi tiết lỗi gặp phải |

**Methods:**

| Method | Mô tả |
|--------|-------|
| hasOversell() | Có oversell không (phải false) |
| getSuccessRate() | Tỉ lệ thành công |

---

### FrameworkIntegrationTest *(abstract class)*

**Mô tả:** Base class cho integration test. Setup sẵn toàn bộ
mock infrastructure — developer chỉ cần implement use case cụ thể.

**Framework setup sẵn:**
- InMemoryEventBusAdapter (không cần Kafka)
- MockPaymentGateway (không cần gateway thật)
- FrameworkTestSupport (helper methods)
- H2 in-memory DB (không cần PostgreSQL)

**Methods developer implement:**

| Method | Mô tả |
|--------|-------|
| getOrchestrator() | Trả về orchestrator cần test |
| buildTestRequest(resourceId, quantity) | Tạo test request |
| getInitialStock() | Số lượng inventory ban đầu |

**Given/When/Then helpers:**

| Method | Mô tả |
|--------|-------|
| givenAvailableStock(quantity) | Khởi tạo inventory |
| givenPaymentWillSucceed() | Config mock payment thành công |
| givenPaymentWillFail() | Config mock payment thất bại |
| givenPaymentWillTimeout() | Giả lập Tình huống A |
| givenPaymentWillSucceedLate() | Giả lập Tình huống B |
| thenAssertNoOversell() | Verify không oversell |
| thenAssertEventPublished(type) | Verify event đã publish |

---

---

# Module 10 — Auto Configuration

---

## 10.1 Tổng quan

### Vai trò trong hệ thống

Auto Configuration biến framework từ "bộ code cần tự wire" thành
"thư viện chỉ cần add dependency". Developer không cần tự tạo bean,
không cần tự inject dependency — framework tự làm tất cả.

### Tại sao quan trọng?

Không có Auto Configuration, developer phải:
1. Tạo InventoryStrategy bean thủ công
2. Inject đúng DataSource vào strategy
3. Tạo EventBus bean với config phù hợp
4. Wire EventBus vào Saga
5. Setup Reconciliation scheduler
6. Register metrics...

→ 50-100 dòng boilerplate code, dễ sai.

Với Auto Configuration:
1. Add dependency vào pom.xml
2. Thêm @EnableHighConcurrencyResource
3. Viết config yaml
→ Framework tự lo tất cả.

### Nguyên tắc: Convention over Configuration

Mọi config đều có giá trị mặc định hợp lý — developer chỉ override
những gì khác với default. Ví dụ: nếu không config gì, framework
dùng PessimisticLockStrategy với các setting an toàn nhất.

---

## 10.2 Danh sách thành phần

### @EnableHighConcurrencyResource *(annotation)*

**Mô tả:** Annotation đặt trên main class của ứng dụng Spring Boot.
Trigger toàn bộ auto-configuration của framework.

**Tác dụng khi thêm annotation:**
- Import HcrAutoConfiguration
- Scan và register tất cả bean của framework
- Load HcrProperties từ application.yaml
- Setup auto-configured components theo config

**Cách dùng:**
```
Thêm 1 dòng vào main class:
@SpringBootApplication
@EnableHighConcurrencyResource  ← chỉ cần dòng này
public class MyApplication { ... }
```

---

### HcrAutoConfiguration *(class)*

**Mô tả:** @Configuration class tự động load khi có HCR framework
trong classpath. Wire tất cả bean theo config yaml.

**Điều kiện load:**
- @ConditionalOnClass: chỉ load nếu AbstractSagaOrchestrator trong classpath
- @ConditionalOnMissingBean: không override nếu developer đã tự define bean

**Beans được tạo tự động:**

| Bean | Điều kiện | Mô tả |
|------|-----------|-------|
| InventoryStrategy | Luôn | Theo hcr.inventory.strategy |
| EventBus | Luôn | Theo hcr.event-bus.type |
| PaymentGateway | Chỉ khi không có custom | MockPaymentGateway |
| FrameworkMetrics | Khi có Micrometer | MicrometerFrameworkMetrics |
| RateLimiter | Khi enabled=true | RedisTokenBucketRateLimiter |
| IdempotencyHandler | Luôn | RedisIdempotencyHandler |
| ReconciliationService | Luôn | Theo config |
| InventoryInitializer | Khi strategy=redis-atomic | Auto-run on startup |
| CorrelationIdFilter | Luôn | Propagate correlationId xuyên suốt |
| HcrActuatorEndpoint | Khi có spring-boot-actuator | Expose /actuator/hcr |

**Validation khi startup — Fail Fast:**

Framework kiểm tra các điều kiện sau và throw exception ngay khi
startup nếu vi phạm:

| Điều kiện | Lỗi nếu vi phạm |
|-----------|----------------|
| saga.mode=async nhưng thiếu SagaStateRepository bean | HcrFrameworkException: AsyncSaga requires SagaStateRepository |
| strategy=redis-atomic nhưng không có Redis connection | HcrFrameworkException: RedisAtomicStrategy requires Redis |
| event-bus.type=kafka nhưng không có Kafka connection | HcrFrameworkException: KafkaEventBusAdapter requires Kafka |
| Capabilities mismatch (warning, không fail) | [HCR-WARN] Capability mismatch: ... |

---

### HcrActuatorEndpoint *(class)*

**Mô tả:** Spring Boot Actuator endpoint expose tại
`/actuator/hcr` — cho phép developer xem toàn bộ config đang
active và trạng thái của framework tại runtime.

**Mục đích thiết kế:**
Auto-configuration tiện lợi nhưng có thể gây mất kiểm soát —
developer không biết framework đang wire gì bên dưới. Endpoint này
giải quyết vấn đó bằng cách expose mọi thứ một cách minh bạch.

**Thông tin được expose:**

```
GET /actuator/hcr

Response:
{
  "framework": {
    "version": "0.1.0-SNAPSHOT",
    "enabledAt": "2026-04-01T10:00:00Z"
  },

  "inventory": {
    "activeStrategy": "redis-atomic",
    "strategyClass": "io.hcr.inventory.strategy.RedisAtomicStrategy",
    "circuitBreakerEnabled": true,
    "circuitBreakerState": "CLOSED",
    "consistencyLevel": "eventual",
    "consistencyWindowMs": "< 1000ms (normal) | ≤ 300000ms (worst case)"
  },

  "saga": {
    "mode": "async",
    "sagaStateRepositoryPresent": true,
    "sagaStateRepositoryClass": "com.myapp.ConcertSagaStateRepository",
    "reservationTimeoutMinutes": 5
  },

  "eventBus": {
    "adapterType": "kafka",
    "adapterClass": "io.hcr.eventbus.adapter.KafkaEventBusAdapter",
    "capabilities": {
      "supportsOrdering": true,
      "supportsReplay": true,
      "supportsExactlyOnce": true,
      "supportsPartitioning": true,
      "isSynchronous": false,
      "supportsDLQ": true
    },
    "deliveryGuarantee": "at-least-once"
  },

  "payment": {
    "gatewayClass": "com.myapp.VNPayGateway",
    "timeoutMs": 30000,
    "pollingIntervalMs": 5000,
    "maxPollingAttempts": 6
  },

  "gateway": {
    "rateLimiterEnabled": true,
    "rateLimiterClass": "io.hcr.gateway.ratelimit.RedisTokenBucketRateLimiter",
    "permitsPerSecond": 100,
    "burstCapacity": 200,
    "idempotencyTtlSeconds": 86400
  },

  "reconciliation": {
    "enabled": true,
    "timeoutMinutes": 5,
    "scheduleDelayMs": 300000,
    "lastRunAt": "2026-04-01T10:05:00Z",
    "lastRunResult": {
      "totalScanned": 3,
      "totalFixed": 1,
      "duration": "PT0.234S"
    }
  },

  "observability": {
    "metricsEnabled": true,
    "metricsClass": "io.hcr.observability.metrics.MicrometerFrameworkMetrics",
    "prometheusEnabled": true,
    "correlationIdPropagationEnabled": true
  }
}
```

**Điều kiện expose:**
- Chỉ available khi `spring-boot-actuator` trong classpath
- Mặc định chỉ expose qua management port (không phải app port)
- Developer có thể disable: `management.endpoint.hcr.enabled=false`

---

### CorrelationIdFilter *(class)*

**Mô tả:** Servlet filter tự động được register bởi AutoConfiguration.
Đảm bảo mọi request đều có correlationId và correlationId được
propagate xuyên suốt toàn bộ hệ thống.

**Cơ chế hoạt động:**

```
Request đến:
  1. Kiểm tra HTTP header "X-Correlation-ID"
     → Có: dùng giá trị từ header (client cung cấp)
     → Không: sinh UUID mới

  2. Set correlationId vào:
     → MDC (Mapped Diagnostic Context) của SLF4J
        → Mọi log statement tự động include correlationId
     → Request attribute (để các layer khác truy cập)
     → ThreadLocal của framework

  3. Khi publish DomainEvent:
     → Tự động gắn correlationId vào event
     → Khi consumer nhận event → set MDC từ correlationId của event

  4. Khi gọi PaymentGateway:
     → Tự động thêm "X-Correlation-ID" header vào HTTP request
     → Cho phép correlate với log phía payment gateway

  5. Khi response:
     → Thêm "X-Correlation-ID" header vào HTTP response
     → Client dùng để report lỗi với support team
```

**Log format với correlationId:**
```
2026-04-01 10:00:01.234 INFO  [correlationId=abc-123-def] 
  io.hcr.saga.SynchronousSagaOrchestrator - Starting saga for order ord-456
  
2026-04-01 10:00:01.245 INFO  [correlationId=abc-123-def]
  io.hcr.inventory.strategy.RedisAtomicStrategy - Reserved 2 units of resource res-789
  
2026-04-01 10:00:01.312 INFO  [correlationId=abc-123-def]
  io.hcr.payment.AbstractPaymentGateway - Charging 500000 VND, txId=ord-456
```

Developer có thể grep toàn bộ log của 1 request:
```
grep "correlationId=abc-123-def" application.log
```

---

**Mô tả:** Map toàn bộ config yaml thành Java object. Validated
khi startup — fail fast nếu config sai thay vì fail lúc runtime.

**Cấu trúc config đầy đủ:**

**Nhóm Inventory:**

| Config | Kiểu | Default | Mô tả |
|--------|------|---------|-------|
| hcr.inventory.strategy | String | pessimistic-lock | Strategy sử dụng |
| hcr.inventory.circuit-breaker.enabled | boolean | false | Bật CB không |
| hcr.inventory.circuit-breaker.failure-rate-threshold | int | 50 | % lỗi để mở CB |
| hcr.inventory.circuit-breaker.wait-duration-seconds | int | 60 | Thời gian CB OPEN |
| hcr.inventory.circuit-breaker.sliding-window-size | int | 10 | Số request tính rate |
| hcr.inventory.redis.key-prefix | String | hcr:inventory: | Redis key prefix |

**Nhóm Saga:**

| Config | Kiểu | Default | Mô tả |
|--------|------|---------|-------|
| hcr.saga.mode | String | sync | sync hoặc async |
| hcr.saga.reservation-timeout-minutes | int | 5 | Timeout giữ chỗ |
| hcr.saga.allow-partial-fulfillment | boolean | false | Chấp nhận đặt một phần |

**Nhóm Payment:**

| Config | Kiểu | Default | Mô tả |
|--------|------|---------|-------|
| hcr.payment.timeout-ms | long | 30000 | Timeout gọi gateway |
| hcr.payment.max-retries | int | 3 | Số lần retry |
| hcr.payment.polling-interval-ms | long | 5000 | Khoảng cách polling |
| hcr.payment.max-polling-attempts | int | 6 | Số lần poll tối đa |

**Nhóm Event Bus:**

| Config | Kiểu | Default | Mô tả |
|--------|------|---------|-------|
| hcr.event-bus.type | String | kafka | kafka/rabbitmq/redis-stream/in-memory |
| hcr.event-bus.kafka.bootstrap-servers | String | localhost:9092 | Kafka brokers |
| hcr.event-bus.kafka.topic-prefix | String | hcr. | Prefix cho topic name |
| hcr.event-bus.rabbitmq.host | String | localhost | RabbitMQ host |
| hcr.event-bus.rabbitmq.port | int | 5672 | RabbitMQ port |
| hcr.event-bus.rabbitmq.exchange | String | hcr.events | Exchange name |
| hcr.event-bus.redis-stream.key-prefix | String | hcr:stream: | Stream key prefix |
| hcr.event-bus.redis-stream.consumer-group | String | hcr-consumers | Consumer group |

**Nhóm Gateway:**

| Config | Kiểu | Default | Mô tả |
|--------|------|---------|-------|
| hcr.gateway.rate-limiter.enabled | boolean | true | Bật rate limit |
| hcr.gateway.rate-limiter.permits-per-second | long | 100 | Request/giây |
| hcr.gateway.rate-limiter.burst-capacity | long | 200 | Burst tối đa |
| hcr.gateway.idempotency-ttl-seconds | long | 86400 | TTL idempotency cache |

**Nhóm Reconciliation:**

| Config | Kiểu | Default | Mô tả |
|--------|------|---------|-------|
| hcr.reconciliation.timeout-minutes | int | 5 | Order PENDING quá bao lâu |
| hcr.reconciliation.schedule-delay-ms | long | 300000 | Tần suất chạy |
| hcr.reconciliation.inventory-mismatch-threshold | long | 0 | Delta tự động fix |

---

---

*Tài liệu này sẽ được cập nhật liên tục trong quá trình phát triển.*
*Phiên bản: 0.1.0-SNAPSHOT | Revision 2 | SOICT — HUST | 04/2026*
