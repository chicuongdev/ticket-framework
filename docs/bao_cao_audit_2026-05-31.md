# Đối chiếu Báo cáo ↔ Code ↔ Docs — Danh sách lỗi & việc cần sửa

> **Ngày kiểm tra:** 2026-05-31
> **Phạm vi:** Toàn bộ code framework `hcr-*`, ứng dụng `hcr-product`, tài liệu `docs/`, và báo cáo LaTeX `BaoCao/`.
> **Nguyên tắc đối chiếu:** **Code là nguồn sự thật.** Mọi sai lệch giữa báo cáo/docs so với code đều được tính là lỗi của báo cáo/docs.
> **Số dòng** tham chiếu theo file `.tex` tại thời điểm kiểm tra (có thể lệch nhẹ sau khi sửa).

---

## 0. Bảng tổng hợp mức ưu tiên

| # | Vị trí | Vấn đề | Mức độ |
|--:|--------|--------|:------:|
| 1 | Ch3 §3.3, dòng 134 | Giá trị config strategy sai (`pessimistic`/`optimistic` thay vì `…-lock`) | 🔴 Cao |
| 2 | Ch3 §3.3, dòng 178 | Lua reserve: nói trả `-1` khi hết hàng (thực tế `-2`) | 🔴 Cao |
| 3 | Ch3 §3.4, dòng 228 | StepResult nói có 4 trạng thái `SUCCESS/RETRY/FAIL/SKIP` (thực tế 3: `SUCCESS/FAILED/RETRY`) | 🔴 Cao |
| 4 | Ch5 §5.2, dòng 132 | Mock gateway "90% SUCCESS, 5% FAILED, 5% TIMEOUT + seed cố định" (thực tế 80%/20%, không seed) | 🔴 Cao |
| 5 | Ch5 §5.2, dòng 96 | Switch prototype qua `hcr.product.active-prototype` (không tồn tại; thực tế env `ACTIVE_PROTOTYPE`/Spring profile) | 🔴 Cao |
| 6 | Ch5 §5.6, dòng 524 | "tất cả chạy trên cùng một máy vật lý" — mâu thuẫn với bảng 4 VM & deploy thực | 🔴 Cao |
| 7 | Ch3 §3.6, dòng 421 | Cơ chế idempotency IN_FLIGHT/`hcr:idem:` gán cho framework (thực tế nằm ở product, prefix `hcr:idempotency:`) | 🟠 Vừa |
| 8 | Ch3/Ch1/Tóm tắt | "Chín module": đếm Circuit Breaker là module, bỏ sót module `hcr-payment` | 🟠 Vừa |
| 9 | Ch3 dòng 283–285, 491–496, 581–599 | Đoạn văn + `\section`/`\label` bị lặp (copy-paste) | 🟠 Vừa |
| 10 | Ch4, Ch6 | Chương "Phân tích lý thuyết" và "Kết luận" còn trống (chỉ có text mẫu) | 🟠 Vừa |
| 11 | Ch5 dòng 224–522 | Toàn bộ bảng kết quả đang bị comment `%`, còn `[điền]` | 🟠 Vừa |
| 12 | Ch3 dòng 83 | "chín cột chuẩn" của AbstractOrder (thực tế 11 cột) | 🟡 Thấp |
| 13 | Ch5 dòng 23 | Prose hardcode "(4.1)…(4.6)" trong khi đây là Chương 5 | 🟡 Thấp |
| 14 | Ch5 dòng 192 | Kịch bản burst 1.000 RPS khác với run thực (burst_10x → 10.000 RPS) | 🟡 Thấp |
| 15 | Ch5 dòng 144, 156 | Mapping `MockPaymentGateway`/`ConcertTicket` chưa khớp vị trí thực | 🟡 Thấp |
| 16 | Toàn cục | Placeholder: tên concert, `\AUTHOR{Trần Văn A}` | 🟡 Thấp |

---

## A. Lỗi kỹ thuật trong BÁO CÁO (sai so với code)

### A1. 🔴 Giá trị `hcr.inventory.strategy` ghi sai (Ch3 §3.3, dòng 134)

**Báo cáo viết:** đổi `hcr.inventory.strategy` thành `pessimistic`, `optimistic`, hoặc `redis-atomic`.

**Code thực tế** — `hcr-inventory/.../factory/InventoryStrategyFactory.java:29-31`:
```java
public static final String PESSIMISTIC = "pessimistic-lock";
public static final String OPTIMISTIC  = "optimistic-lock";
public static final String REDIS       = "redis-atomic";
```
`switch(name)` so khớp **chính xác** các chuỗi này — không có chuẩn hoá. `application-p1.yml` của product cũng dùng `strategy: pessimistic-lock`.

> ⚠️ Lưu ý 2 hệ tên dễ nhầm: `getStrategyName()` trả về `"pessimistic"`/`"optimistic"`/`"redis-atomic"` (tag cho **metrics**), KHÁC với **config key** `pessimistic-lock`/`optimistic-lock`/`redis-atomic`. Báo cáo đang lấy nhầm tên metrics làm giá trị config.

**Cần sửa:** Ch3 dòng 134 → `pessimistic-lock`, `optimistic-lock`, `redis-atomic`. (Ch5 dòng 176 đã **đúng** — chính Ch3 mới sai, gây mâu thuẫn nội bộ giữa hai chương.)

---

### A2. 🔴 Mã trả về của `inventory_reserve.lua` (Ch3 §3.3, dòng 178)

**Báo cáo viết:** "nếu tồn kho đủ thì DECRBY và trả về giá trị mới, ngược lại trả về **`-1`** để báo không đủ hàng."

**Code thực tế** — `hcr-inventory/src/main/resources/lua/inventory_reserve.lua`:
```
-1  ERROR  — key chưa được khởi tạo (chưa init)
-2  FAIL   — không đủ hàng (INSUFFICIENT)
>=0 SUCCESS
```
→ Hết hàng trả **`-2`**, còn **`-1`** nghĩa là *key chưa init*. Báo cáo nhầm ý nghĩa hai mã.

**Cần sửa:** đổi thành "ngược lại trả về `-2` để báo không đủ hàng; `-1` dành cho trường hợp key chưa được khởi tạo."

---

### A3. 🔴 Số trạng thái của `StepResult` (Ch3 §3.4, dòng 228)

**Báo cáo viết:** `StepResult` phân biệt **bốn** trạng thái: `SUCCESS`, `RETRY`, `FAIL`, `SKIP`.

**Code thực tế** — `hcr-saga/.../step/StepResult.java`:
```java
enum StepStatus { SUCCESS, FAILED, RETRY }   // chỉ 3 trạng thái
```
Không có `SKIP`; và tên là `FAILED` chứ không phải `FAIL`. (Javadoc còn ghi rõ `RETRY` "chua implement retry logic".)

**Cần sửa:** liệt kê đúng **ba** trạng thái `SUCCESS / FAILED / RETRY`. Bỏ `SKIP`, sửa `FAIL`→`FAILED`.

---

### A4. 🔴 Phân bố Mock Payment Gateway & "seed cố định" (Ch5 §5.2, dòng 132)

**Báo cáo viết:** Mock gateway cấu hình **seed cố định**: **90% SUCCESS, 5% FAILED, 5% TIMEOUT**.

**Code thực tế** — `hcr-autoconfigure/.../HcrAutoConfiguration.java:95-104` (bean được ms-payment dùng, vì `hcr.payment.mock-enabled=true`, không override):
```java
MockPaymentGateway.builder()
    .successRate(0.80)        // 80% success
    .simulatedDelayMs(0)      // độ trễ = 0, không phải 100–500ms
    .timeoutRate(0.0)         // 0% timeout
    .noResponseRate(0.0)
    .lateSuccessRate(0.0)
    ...
```
- `MockPaymentGateway` dùng `ThreadLocalRandom.current().nextDouble()` → **KHÔNG có seed**, không thể đặt seed cố định.
- Phân bố thực tế ≈ **80% success / 20% failed / 0% timeout**.
- **Chính file kết quả của Anh** xác nhận: `docs/results/p1_run_20260525.md` ghi *"Mock payment success rate ~80% (random 20% FAIL)"*.

**Cần sửa (chọn 1):**
- **(Khuyến nghị)** Sửa báo cáo cho khớp code + run thực: "~80% SUCCESS, ~20% FAILED, không timeout; kết quả không cố định (`ThreadLocalRandom`)." — và bỏ cụm "seed cố định".
- Hoặc nếu muốn giữ kịch bản 90/5/5 + tái lập được: phải **sửa code** (đặt rate 0.90/0.05/0.05 trong autoconfigure **và** thêm cơ chế seed cho MockPaymentGateway — hiện chưa có). Nếu chọn hướng này thì phải chạy lại toàn bộ thực nghiệm.

---

### A5. 🔴 Cơ chế switch prototype `hcr.product.active-prototype` (Ch5 §5.2, dòng 96)

**Báo cáo viết:** "chuyển đổi giữa ba luồng thông qua thuộc tính cấu hình `hcr.product.active-prototype`."

**Code thực tế:** Không tồn tại property `active-prototype` ở bất kỳ file `.java`/`.yml` nào (đã grep toàn repo). Cơ chế thật:
- `ms-order/application.yml:22` → `profiles.active: ${ACTIVE_PROTOTYPE:p1}`
- Chọn profile `p1`/`p2`/`p3` qua **biến môi trường `ACTIVE_PROTOTYPE`**, mỗi profile set `hcr.inventory.strategy` + `hcr.saga.mode`.

→ Ch5 **tự mâu thuẫn**: dòng 166 nói đúng ("cấu hình Spring profile"), dòng 96 nói sai.

**Cần sửa:** dòng 96 → "qua biến môi trường `ACTIVE_PROTOTYPE` chọn Spring profile `p1`/`p2`/`p3`."

---

### A6. 🔴 Mâu thuẫn môi trường: "cùng một máy vật lý" vs 4 VM (Ch5 §5.6 Giới hạn, dòng 524)

**Báo cáo viết (phần giới hạn):** "tất cả service và hạ tầng chạy trên **cùng một máy vật lý**, dẫn đến contention…"

**Mâu thuẫn với:** chính Bảng 5.x (dòng 70–91) mô tả **4 VM GCP tách biệt** (`hcr-app`, `hcr-data`, `hcr-busobs`, `hcr-loadgen`) — và khớp deploy thực (`docs/results/burst_10x_comparison_20260528.md`: 4 VM, IP 10.20.0.2–5, zone asia-southeast1-a).

**Cần sửa:** viết lại câu giới hạn theo đúng hiện trạng — ví dụ: "thực nghiệm chạy trên 4 VM trong **cùng một zone (single region)**; chưa đánh giá multi-region; ba microservice chia sẻ một VM `hcr-app` nên vẫn có contention CPU giữa chúng." Bỏ hẳn cụm "cùng một máy vật lý".

---

### A7. 🟠 Cơ chế Idempotency của Gateway Module gán nhầm tầng (Ch3 §3.6.2, dòng 421)

**Báo cáo viết:** Framework Gateway dùng `SETNX hcr:idem:{key} = "IN_FLIGHT"` TTL 24h; nếu giá trị `IN_FLIGHT`→409, nếu `orderId`→200; cập nhật `IN_FLIGHT`→`orderId`; xoá key khi saga fail.

**Code thực tế** — `hcr-gateway/.../redis/RedisIdempotencyHandler.java`:
- Prefix key là **`hcr:idempotency:`**, KHÔNG phải `hcr:idem:`.
- Chỉ có `SET ... EX` (markProcessed) + `EXISTS` (isDuplicate) + `GET` + `DELETE`. **Không có state `IN_FLIGHT`, không dùng SETNX.**
- Vòng đời `IN_FLIGHT → orderId` thực ra ở **tầng product**: `ms-order/.../OrderController.java` dùng `setIfAbsent(key, "PROCESSING", TTL)` (giá trị literal là `"PROCESSING"`, không phải `"IN_FLIGHT"`).

→ Báo cáo đang mô tả thiết kế idempotency của **ứng dụng (ms-order)** nhưng quy cho **framework Gateway Module**.

**Cần sửa:** hoặc (a) nói rõ "vòng đời IN_FLIGHT là phần triển khai ở ứng dụng demo (ms-order); `RedisIdempotencyHandler` của framework cung cấp hợp đồng đơn giản hơn (SET/EXISTS/GET/DELETE)"; hoặc (b) tách mô tả: framework dùng prefix `hcr:idempotency:`, product dùng claim `PROCESSING` qua SETNX.

---

## B. Vấn đề về CẤU TRÚC / NHẤT QUÁN trong báo cáo

### B1. 🟠 "Chín module" — thành phần chưa khớp module thật (Ch3 dòng 53 & 672; Ch1 dòng 65, 84; Tóm tắt dòng 13)

`pom.xml` gốc có **12 module**: `core, inventory, eventbus, payment, saga, gateway, reconciliation, observability, testing, autoconfigure, spring-boot-starter, sample`.

Cách đếm "chín module" trong báo cáo (Core Domain + Inventory + Saga + EventBus + Gateway + **Circuit Breaker Decorator** + Reconciliation + Monitoring + AutoConfiguration):
- **Đếm "Circuit Breaker Decorator" như một module**, nhưng nó chỉ là 1 class trong `hcr-inventory` (`decorator/CircuitBreakerInventoryDecorator.java`), không phải Maven module.
- **Bỏ sót module `hcr-payment`** (có `PaymentGateway`, `AbstractPaymentGateway`, `TimeoutHandler`, `MockPaymentGateway`) — module này chỉ được nhắc gián tiếp trong Saga.

Ngoài ra **dòng 672 mâu thuẫn dòng 53**: kết chương liệt kê Reliability = Reconciliation + Monitoring (thiếu AutoConfiguration), còn mở đầu liệt kê đủ 3.

**Cần sửa:** thống nhất một định nghĩa "chín module reusable" khớp pom — đề xuất: `core, inventory, eventbus, payment, saga, gateway, reconciliation, observability, autoconfigure` (9 module chức năng; `testing/starter/sample` là phụ trợ). Coi Circuit Breaker là **decorator thuộc Inventory** (mục con), không phải module độc lập. Bổ sung Payment vào danh sách module.

---

### B2. 🟠 Đoạn văn / lệnh LaTeX bị lặp (copy-paste)

| Vị trí | Hiện trạng |
|--------|-----------|
| Ch3 **dòng 283–285** | Lặp lại nguyên đoạn mở đầu Saga (đã có ở 207–209) + thêm `\label{sec:saga}` trùng |
| Ch3 **dòng 491–496** | `\section{Reconciliation Module}` + đoạn mở đầu xuất hiện **2 lần liên tiếp** (491–492 rồi 493–496) + `\label{sec:reconciliation}` trùng |
| Ch3 **dòng 581–599** | Sau bảng policy lại lặp "Cấu trúc tổng thể" + figure `reconciliation_class` + đoạn distributed lock/scheduling (đã trình bày ở §3.8.2 dòng 512–519) |

**Cần sửa:** xoá các đoạn lặp; mỗi `\label` chỉ giữ một bản. §3.8 (Reconciliation) cần sắp xếp lại để không trùng "Cấu trúc tổng thể" và "Distributed lock".

---

## C. Phần CÒN THIẾU / CHƯA HOÀN THIỆN trong báo cáo

### C1. 🟠 Chương 4 "PHÂN TÍCH LÝ THUYẾT" còn trống
`Chuong/4_Phan_tich_ly_thuyet.tex` chỉ có text mẫu ("Tên của kết quả phân tích số 1/2"). Nhưng **Ch1 dòng 109** và **Tóm tắt** đã hứa Ch4 gồm: chứng minh tính đúng đắn từng strategy, phân tích consistency window, đánh giá scalability/reuse, so sánh hệ thống.
**Cần sửa:** hoặc viết nội dung Ch4, hoặc bỏ chương này và (i) renumber, (ii) sửa các mô tả trong Ch1/Tóm tắt, (iii) sửa "(4.x)" trong Ch5 (xem C4).

### C2. 🟠 Chương 6 "KẾT LUẬN" còn trống
`Chuong/6_Ket_luan.tex` chỉ có text mẫu. **Cần viết** phần Kết luận + Hướng phát triển.

### C3. 🟠 Toàn bộ bảng kết quả Ch5 đang bị comment (dòng 224–522)
Smoke/sustained/burst, kiểm chứng zero-oversell, MTTR, trả lời 4 câu hỏi nghiên cứu, đối chiếu 5 mục tiêu — đều `%`-comment và `[điền]`.
Dữ liệu thật **đã có** trong `docs/results/`:
- `burst_10x_comparison_20260528.md` (so sánh P1/P2/P3, burst 10×)
- `p1_run_20260525.md` (P1 oversell-check)
**Cần sửa:** bỏ comment, điền số liệu từ `docs/results/` (và chạy bổ sung các cell còn thiếu).

### C4. 🟡 Prose hardcode "(4.1)…(4.6)" ở Ch5 (dòng 23)
File là Chương 5 nên section render thành 5.1–5.6, nhưng prose ghi "(4.1) định nghĩa…(4.6)". (Dấu vết của việc trước đây Thực nghiệm là Chương 4.)
**Cần sửa:** đổi thành "(5.1)…(5.6)" (hoặc dùng `\ref`). Gắn với quyết định ở C1.

---

## D. Sai lệch nhỏ / placeholder

- **D-1 (Ch3 dòng 83):** "chín cột chuẩn" của `AbstractOrder`. Thực tế `hcr-core/.../AbstractOrder.java` có **11 cột** (9 cột đã liệt kê **+ `updatedAt` + `inventoryReleasedAt`**). → Sửa số lượng hoặc dùng "các cột chuẩn".
- **D-2 (Ch5 dòng 192):** Kịch bản burst mô tả 0→1.000 RPS, nhưng run thực `burst_10x` chạy 500→2000→**10.000**→500 RPS. → Đối chiếu lại với script k6 (`hcr-product/load-tests/k6/`) và mô tả đúng kịch bản đã chạy.
- **D-3 (Ch5 dòng 156, mapping):** `AbstractPaymentGateway → MockPaymentGateway` — trong product, bean `PaymentGateway` mà ms-order dùng là `RemotePaymentGateway` (HTTP sang ms-payment); còn `MockPaymentGateway` (subclass của `AbstractPaymentGateway`) là bean **framework** chạy trong ms-payment. → Làm rõ.
- **D-4 (Ch5 dòng 144):** `ConcertTicket` ghi "(ms-inventory/domain/)" — thực tế có **cả hai bản**: `ms-order/.../domain/ConcertTicket.java` và `ms-inventory/.../domain/ConcertTicket.java`. → Ghi chú là có ở cả hai service.
- **D-5 (Ch5 dòng 100, 104):** Tên DB generic `order_db`/`inventory_db`/`payment_db`. DB order thực tế là `order_p1_db` (suffix theo profile, mặc định `DB_NAME`). → Nếu giữ tên generic, nên chú thích.
- **D-6 (Bảng concert, dòng 123–128):** tên concert đang `[điền]`. Đã có trong `ms-inventory/.../data.sql`: concert-001 = *Anh Trai Vu Ngan Cong Gai*, concert-002 = *Born Pink Tour HCMC*, concert-003 = *Acoustic Night*.
- **D-7 (`DoAn.tex:80`):** `\AUTHOR{Trần Văn A}` và chữ ký Tóm tắt còn placeholder → đổi thành **Nguyễn Chí Cường**.
- **D-8 (Ch2 §core, Ch3 dòng 85):** mô tả state machine hơi lỏng — viết như thể `PENDING` đi thẳng tới `CONFIRMED`. Thực tế `OrderStatus.canTransitionTo`: `PENDING → {RESERVED, CANCELLED}` (muốn `CONFIRMED` phải qua `RESERVED`). → Diễn đạt chính xác hơn.

---

## E. Phần cần UPDATE trong `docs/` hiện tại

> Đây là các tài liệu nội bộ đang **sai so với code** — nên sửa để không tiếp tục "nuôi" lỗi sang báo cáo.

### E1. `docs/thesis/04-evaluation.md` — nguồn gốc của lỗi A4
- **Dòng 34:** `Mock payment gateway cố định seed (hcr.payment.mock-seed: 42)` → property **không tồn tại**; mock dùng `ThreadLocalRandom` (không seed). Sửa/bỏ.
- **Dòng 133:** "Mock 90% success, 10% fail/timeout/late-success" → thực tế **80% success / 20% fail**. Sửa.
- **Dòng 318, 386:** tham chiếu `hcr.payment.mock-seed=99` → property không tồn tại. Sửa.

### E2. `docs/thesis/03-decision-log.md`
- **Dòng 73:** `hcr.inventory.strategy: pessimistic | optimistic | redis-atomic` → đúng phải là `pessimistic-lock | optimistic-lock | redis-atomic`.

### E3. `docs/framework_design.md`
- **Dòng 550, 576:** ví dụ `getStrategyName() { return "pessimistic-lock"; }` / `"optimistic-lock"` → code thật trả `"pessimistic"` / `"optimistic"` (tag metrics), **khác** với config key. Cần ghi rõ 2 hệ tên để không nhầm như A1.

### E4. `hcr-inventory/architecture.md` (doc module)
- **Dòng 407:** `hcr.inventory.strategy: pessimistic|optimistic|redis-atomic` → sửa thành các key `-lock`. (Cùng loại lỗi với A1.)

### E5. `docs/PROGRESS.md`
- **Dòng 14:** "12 module (parent pom + 11 child)" → pom liệt kê **12 module con** dưới `<modules>` (+ 1 parent). Diễn đạt số lượng cho nhất quán.

### E6. `CLAUDE.md` (gốc dự án)
- Mục **"Known limitations"** vẫn ghi "Các module 🔲 chưa implement — chỉ có stub". Hiện tất cả module đã ✅ (khớp memory `framework_actual_state`). → Bỏ/cập nhật dòng này.

---

## F. Những điểm báo cáo ĐÃ ĐÚNG (đã verify với code — không cần sửa)

Để Anh yên tâm các phần lõi:
- Ch5 dòng 176: giá trị config `pessimistic-lock/optimistic-lock/redis-atomic` — **đúng**.
- Lua `inventory_release.lua`: có guard `newAvailable > total → cap về total` chống double-release — **đúng** (Ch3 dòng 178, 282).
- Idempotent consumer: INSERT `eventId` + update tồn kho trong **cùng 1 transaction**, dedup qua unique constraint — **đúng** (Ch2 dòng 94, Ch3 dòng 282, 360).
- `OrderStatus`: 6 trạng thái, `canTransitionTo()`, terminal = CONFIRMED/CANCELLED/EXPIRED — **đúng**. `transitionTo()` package-private + `OrderAccessor` — **đúng** (Ch3 dòng 94).
- `EventBus`: `publish` / `publishIdempotent` / `publishBatch` / `subscribe` — **đúng** (Ch3 dòng 314).
- `PaymentInitiationStrategy`: `AutoChargeInitiation` + `UserConfirmInitiation` tồn tại; pool thread `auto-charge-N` — **đúng** (Ch3 dòng 267, Ch5 dòng 102).
- `SagaStateRepository` fail-fast khi thiếu bean (async) — **đúng** (Ch3 dòng 271).
- Reconciliation: `runReconciliation()` `@Scheduled(fixedDelayString=…:300000)` + `final`, Redisson distributed lock, công thức `DB_available = total − CONFIRMED − RESERVED` — **đúng** (Ch3 §3.8).
- Idempotency TTL mặc định 24h (`DEFAULT_TTL_SECONDS=86400`) — **đúng** (Ch3 dòng 421).
- `PaymentAttempt` PK = `orderId` (idempotency tự nhiên ms-payment) — **đúng** (Ch5 dòng 102).
- Số vé seed: concert-001=10.000, concert-002=5.000, concert-003=500 — **đúng** (Ch5 Bảng dòng 123–128).
- P1/P2: ms-order reserve/release trực tiếp trên bảng `concert_tickets` của chính nó (có `ConcertTicket` + repository + sync orchestrator) — **đúng** (Ch5 dòng 100).
- Môi trường 4 VM GCP, Ubuntu 24.04, OpenJDK 17, PG15, Redis7, Kafka KRaft, HikariCP max 50 — **đúng** (khớp `docs/results/burst_10x_comparison_20260528.md`).
