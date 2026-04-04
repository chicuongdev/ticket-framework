# Hướng dẫn đọc code — hcr-payment (Module 04)

> **Mục đích module:** Abstract hóa việc tích hợp payment gateway bên thứ 3.
> Xử lý 2 tình huống nguy hiểm: gateway crash không response (T/H A) và
> response bị mất dù đã charge (T/H B). Dependency duy nhất: `hcr-core`.

---

## Hai tình huống mà module này giải quyết

Đọc phần này trước để hiểu TẠI SAO module được thiết kế như vậy.

**Tình huống A — Gateway crash, không trả response:**
```
charge() → Gateway crash → không có response
→ Không biết tiền đã bị trừ chưa
→ Nếu assume FAILED: mất tiền khách (nếu thực ra đã trừ)
→ Nếu assume SUCCESS: tặng vé miễn phí (nếu chưa trừ)
```

**Tình huống B — Gateway thành công nhưng response bị mất:**
```
charge() → Gateway charge OK → tiền bị trừ → response mất giữa đường
→ Nhận timeout → assume FAILED → cancel order → rollback inventory
→ Khách mất tiền nhưng không có vé
```

**Giải pháp:** TimeoutHandler polling `queryStatus()` → tìm lại kết quả thực tế.

---

## Thứ tự đọc được đề xuất

### Bước 1 — Đọc Models (không có dependency nội bộ, đọc nhanh)

**1.1** `src/main/java/io/hrc/payment/model/PaymentStatus.java`
- Enum 4 trạng thái: SUCCESS, FAILED, TIMEOUT, UNKNOWN.
- Chú ý method `isResolved()` — trả true khi SUCCESS hoặc FAILED. TimeoutHandler dùng method này để biết khi nào dừng polling.
- TIMEOUT ≠ UNKNOWN: TIMEOUT là "gateway chưa trả lời, đang polling", UNKNOWN là "polling xong rồi vẫn không biết → Reconciliation xử lý".

**1.2** `src/main/java/io/hrc/payment/model/HealthStatus.java`
- Enum 3 trạng thái: UP, DEGRADED, DOWN.
- Dùng trong GatewayHealth để quyết định có gọi gateway không.

**1.3** `src/main/java/io/hrc/payment/model/PaymentRequest.java`
- Input chuẩn khi gọi `charge()`.
- Chú ý `transactionId` chính là idempotency key — thường bằng orderId. Gateway dùng field này để đảm bảo không charge 2 lần.
- `metadata` là Map mà developer thêm tùy ý (userId, productId...). Trả về unmodifiable map.

**1.4** `src/main/java/io/hrc/payment/model/PaymentResult.java`
- Result Object pattern — tương tự `ReservationResult` trong hcr-core.
- Factory methods: `success()`, `failed()`, `timeout()`, `unknown()`.
- Chú ý `gatewayTransactionId` — ID phía gateway, dùng để query/refund sau. Chỉ có khi SUCCESS.
- Chú ý `amount` trong success — số tiền gateway thực tế đã trừ (có thể khác request).

**1.5** `src/main/java/io/hrc/payment/model/RefundRequest.java`
- Input cho thao tác refund. Cần `gatewayTransactionId` từ PaymentResult gốc.
- `amount` có thể < amount gốc (partial refund).

**1.6** `src/main/java/io/hrc/payment/model/RefundResult.java`
- Result Object cho refund. 4 status: SUCCESS, FAILED, PENDING, UNKNOWN.
- Chú ý PENDING — một số gateway xử lý refund bất đồng bộ.

**1.7** `src/main/java/io/hrc/payment/model/AuthorizationResult.java`
- Kết quả pre-authorize (giữ tiền trước, charge sau).
- Dùng cho use case khách sạn, thuê xe — không dùng trong concert ticket.
- Chú ý `expiresAt` — authorization có thời hạn do gateway quy định.

**1.8** `src/main/java/io/hrc/payment/model/GatewayHealth.java`
- Health snapshot: status, successRate, latency, connections.
- Static factories: `up()`, `down()` cho common cases.

---

### Bước 2 — Đọc PaymentGateway interface

**2.1** `src/main/java/io/hrc/payment/gateway/PaymentGateway.java`
- Contract với gateway bên thứ 3. 3 nhóm operations:
  - **Core:** `charge()`, `queryStatus()`, `refund()`, `partialRefund()`
  - **Pre-Authorization:** `preAuthorize()`, `capture()`, `voidAuthorization()`
  - **Health:** `isAvailable()`, `getHealth()`, `getGatewayName()`
- Developer KHÔNG implement trực tiếp — extend `AbstractPaymentGateway` thay vì.
- `queryStatus()` là chìa khóa giải quyết T/H A và B.

---

### Bước 3 — Đọc TimeoutHandler (giải pháp cho T/H A và B)

**3.1** `src/main/java/io/hrc/payment/handler/TimeoutHandler.java`
- Khi `charge()` timeout → polling `queryStatus()` mỗi 5 giây, tối đa 6 lần (30 giây).
- Nếu tìm được kết quả (SUCCESS/FAILED) → trả về kết quả thực tế.
- Nếu hết 6 lần vẫn UNKNOWN → trả UNKNOWN → Reconciliation xử lý sau.
- Có 2 mode: `handle()` (sync, block thread) và `handleAsync()` (async, dùng ScheduledExecutorService).
- Chú ý `handleAsync()` dùng recursive `PollingTask` — mỗi lần poll schedule lần tiếp theo, tự shutdown khi xong.

---

### Bước 4 — Đọc AbstractPaymentGateway (Template Method)

**4.1** `src/main/java/io/hrc/payment/gateway/AbstractPaymentGateway.java`
- **Template Method pattern** — framework xử lý sẵn:
  - Timeout detection → bắt `SocketTimeoutException`/`TimeoutException` → delegate sang TimeoutHandler.
  - Retry khi network error (IOException, ConnectException, SocketException). Exponential backoff.
  - Logging: mọi call được log với txId, duration, result.
- **Developer chỉ implement 3 method:**
  - `doCharge(PaymentRequest)` — gọi API charge của gateway cụ thể
  - `doQuery(String transactionId)` — gọi API query
  - `doRefund(RefundRequest)` — gọi API refund
- Chú ý: `charge()` là `final` — developer không override được. Framework đảm bảo pipeline luôn chạy đúng.
- Chú ý: `refund()` KHÔNG retry — tránh double refund.
- Pre-authorization methods default throw `UnsupportedOperationException`. Gateway nào hỗ trợ thì override.

---

### Bước 5 — Đọc MockPaymentGateway (testing)

**5.1** `src/main/java/io/hrc/payment/gateway/mock/MockPaymentGateway.java`
- Implementation giả lập — dùng cho testing và benchmark.
- Configurable: successRate (80%), timeoutRate (5%), noResponseRate (2%), lateSuccessRate (3%).
- **Điểm hay:** lưu kết quả vào `transactionLog` (ConcurrentHashMap) → `queryStatus()` trả về đúng kết quả thực tế — simulate hành vi gateway thật.
- T/H A: throw timeout, KHÔNG lưu vào log → queryStatus trả UNKNOWN (đúng: gateway chưa xử lý).
- T/H B: lưu SUCCESS vào log, RỒI throw timeout → queryStatus trả SUCCESS (đúng: đã charge, response mất).
- Testing utilities: `getTransactionLog()`, `clearTransactionLog()`, `getProcessedCount()`.
- Dùng Builder pattern để configure.

---

## Dependency Graph

```
hcr-core
   │
   ▼
hcr-payment
   ├── model/           ← Data classes (không có logic phức tạp)
   ├── gateway/          ← Interface + Abstract base
   │   └── mock/         ← Testing implementation
   └── handler/          ← TimeoutHandler (polling logic)
```

## Quyết định thiết kế quan trọng

| Quyết định | Lý do |
|-----------|-------|
| `charge()` là `final` trong AbstractPaymentGateway | Đảm bảo pipeline timeout/retry/logging luôn chạy đúng, developer không vô tình bỏ qua |
| `refund()` KHÔNG retry | Double refund nguy hiểm hơn refund failed — để Reconciliation xử lý |
| MockPaymentGateway lưu transactionLog | Để `queryStatus()` trả đúng kết quả → test được T/H A vs T/H B |
| Pre-auth default UnsupportedOperationException | Không phải gateway nào cũng hỗ trợ. Explicit failure tốt hơn silent no-op |
| PaymentResult dùng factory methods | Consistency với ReservationResult trong hcr-core. Private constructor → immutable |
