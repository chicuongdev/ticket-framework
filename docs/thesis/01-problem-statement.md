# Chương 1 — Giới thiệu

> **Mục đích tài liệu:** Cung cấp toàn bộ chất liệu thô cho **Chương 1 — Giới thiệu** của báo cáo đồ án tốt nghiệp. Khi prompt LLM viết báo cáo, dùng nội dung file này làm context. Các mục có dấu `[…]` cần sinh viên tự điền.
>
> **Mapping với cấu trúc 5 chương của đồ án:**
> 1. **Giới thiệu** ← *file này*
> 2. **Cơ sở lý thuyết** ← `02-theoretical-foundation.md` (sẽ viết tiếp)
> 3. **Kiến trúc đề xuất (tổng quan + chi tiết từng module)** ← `architecture.md` (root) + 8 file `[module]/architecture.md` đã có + `03-decision-log.md` (sẽ viết)
> 4. **Phân tích thực nghiệm** ← `04-evaluation.md` (sẽ viết, cần load test data)
> 5. **Kết luận** ← tổng hợp khi xong
>
> **Đề tài:** `[TÊN ĐỀ TÀI CHÍNH THỨC, ví dụ: "Xây dựng framework xử lý phân phát tài nguyên có giới hạn dưới tải cao trên nền Spring Boot — HCR Framework"]`
>
> **GVHD:** `[Họ tên GVHD]` &nbsp;·&nbsp; **Sinh viên:** `[Họ tên — MSSV]` &nbsp;·&nbsp; **Lớp:** `[…]`

---

## 1.1. Bối cảnh và lý do chọn đề tài

### 1.1.1. Bùng nổ các sự kiện "tài nguyên giới hạn — cầu vượt cung tức thời"

Trong nửa thập kỷ qua (2020–2025), nhiều sự kiện đã làm nổi bật một lớp bài toán kinh điển trong các hệ thống thương mại điện tử và đặt chỗ trực tuyến: **phân phát một lượng tài nguyên giới hạn cho rất nhiều người dùng đồng thời, trong một khoảng thời gian rất ngắn**. Đặc trưng chung: số lượng *cầu* gấp 10–1000 lần *cung*; tải đến đỉnh (peak load) trong vài giây, sau đó giảm nhanh. Một vài ví dụ tiêu biểu:

| Sự kiện | Cung (giới hạn) | Cầu (tải đỉnh) |
|---|---|---|
| Bán vé concert BTS, BLACKPINK, Taylor Swift (2022–2024) | 50 000–80 000 vé / show | Hàng triệu request đồng thời, server sập 5–30 phút |
| Flash sale 0 đồng VietJet, Bamboo Airways (Việt Nam, hằng năm) | Vài nghìn vé | Hàng trăm nghìn request /s |
| Mở đăng ký tín chỉ đầu kỳ ở các trường đại học lớn (HUST, VNU, …) | Số slot / lớp giới hạn | Hàng chục nghìn sinh viên cùng F5 |
| Đặt slot khám bệnh online tại các bệnh viện công | Vài chục slot / bác sĩ / ngày | Hàng nghìn yêu cầu lúc 7h sáng |
| Sàn TMĐT giảm giá lớn 11.11, Black Friday | Vài trăm sản phẩm hot | Vài triệu request /s |

Các hệ thống này, dù khác nhau về domain (giải trí, hàng không, giáo dục, y tế, bán lẻ), đều có chung một **bài toán kỹ thuật cốt lõi**:

> *"Cấp phát một lượng tài nguyên hữu hạn cho nhiều khách hàng đồng thời, đảm bảo không bao giờ phát ra nhiều hơn lượng có sẵn (zero-oversell), trong điều kiện tải đỉnh có thể đạt vài nghìn đến vài chục nghìn request mỗi giây."*

### 1.1.2. Hậu quả thực tế khi giải quyết sai

Khi hệ thống không xử lý được bài toán trên, hậu quả có ba dạng:

1. **Oversell** — bán quá số lượng có thật. Ví dụ: concert chỉ có 100 ghế VIP nhưng hệ thống xác nhận 130 vé. Kết quả: kiện tụng, hoàn tiền, mất uy tín thương hiệu, mất khách hàng dài hạn.
2. **Server sập** — tải đỉnh vượt khả năng → request bị reject hàng loạt → tin tức tiêu cực, mất doanh thu của cả đợt sale (case Ticketmaster — Taylor Swift Eras Tour 2022 thiệt hại ước tính rất lớn và phải điều trần Quốc hội Mỹ).
3. **Inconsistency âm thầm** — không sập, không oversell rõ ràng, nhưng tồn kho ghi nhận sai (Redis 30 vé, DB 50 vé) → khi reconcile cuối ngày phát hiện thiệt hại, đã quá muộn.

### 1.1.3. Khoảng trống của các giải pháp hiện hữu

Nhà phát triển ứng dụng Việt Nam và quốc tế hiện đang giải quyết bài toán này theo ba hướng, đều có hạn chế:

- **Hướng 1 — Tự code từng project:** Viết khóa pessimistic / optimistic, retry, idempotency, saga, reconciliation cho riêng từng dự án. Hạn chế: tốn 3–6 tháng kỹ sư, dễ sót edge case, không tái sử dụng được khi sang dự án mới.
- **Hướng 2 — Dùng framework saga đa năng** (Eventuate Tram, Axon, Camunda 8, Temporal, Apache Seata): Mạnh về workflow nhưng không tập trung vào *zero-oversell at high concurrency*. Inventory layer, lựa chọn chiến lược consistency và reconciliation vẫn là việc developer.
- **Hướng 3 — Dùng platform thương mại** (AWS Step Functions + DynamoDB conditional writes, Stripe Atlas, …): Khoá vào nhà cung cấp, chi phí cao, không có giải pháp on-premise dành cho ngân hàng / bệnh viện công Việt Nam (vốn yêu cầu chủ quyền dữ liệu).

→ **Khoảng trống:** thiếu một *framework Spring Boot mã nguồn mở*, **tập trung vào một bài toán cụ thể** (zero-oversell at high concurrency), cho phép developer chọn chiến lược đánh đổi giữa *throughput* và *consistency* một cách minh bạch, đi kèm sẵn cơ chế *reconciliation* để cứu hệ thống khỏi inconsistency âm thầm.

### 1.1.4. Lý do cá nhân chọn đề tài

`[Để sinh viên tự điền — gợi ý: trải nghiệm thực tế gặp lỗi oversell khi mua vé / quan tâm tới hệ thống phân tán / mong muốn xây dựng sản phẩm tái sử dụng cho cộng đồng dev VN]`

---

## 1.2. Phát biểu bài toán

### 1.2.1. Định nghĩa bài toán

Cho một hệ thống có một (hoặc nhiều) **resource** $R$ với tổng tồn kho $T$. Các yêu cầu (request) đặt mua đồng thời được mô hình hóa thành dòng các *reservation request* $r_1, r_2, \ldots, r_n$, mỗi $r_i$ yêu cầu một số lượng $q_i$. Tại bất kỳ thời điểm nào:

$$
\sum_{i \in \mathcal{C}} q_i \le T
$$

trong đó $\mathcal{C}$ là tập các request được hệ thống xác nhận thành công (CONFIRMED). Hệ thống được coi là **đúng đắn** (correct) khi và chỉ khi bất đẳng thức trên KHÔNG BAO GIỜ bị vi phạm — kể cả khi:

- Có hàng nghìn $r_i$ đến trong cùng một khoảng vài giây.
- Có lỗi mạng giữa hệ thống và payment gateway.
- Có một node trong cluster bị crash giữa quá trình xử lý.
- Có hai instance của cùng một service xử lý cùng một request (do client retry).

### 1.2.2. Bài toán phụ — đánh đổi consistency vs throughput

Bài toán đặt ra không chỉ là "cấm oversell" — vì cấm oversell thì có thể chỉ cần một bảng SQL với `SELECT … FOR UPDATE`. Câu hỏi thực sự là:

> *"Trong tập các giải pháp đảm bảo zero-oversell, giải pháp nào cho throughput cao nhất ứng với từng giới hạn về consistency mà nghiệp vụ chấp nhận được?"*

Cụ thể, ba lớp use case có yêu cầu khác biệt:

- **Strong consistency, throughput thấp – trung bình:** Đặt phòng khách sạn, đặt slot khám bệnh — request/giây ≤ 1000. Yêu cầu: client F5 nhiều lần phải thấy trạng thái mới nhất ngay lập tức.
- **Strong consistency, throughput trung bình:** Đặt vé tàu, đăng ký tín chỉ — request/giây 1000–5000. Vẫn yêu cầu strong consistency nhưng phải scale tốt hơn.
- **Eventual consistency, throughput cao:** Vé concert, flash sale 11.11 — request/giây 5000–10000+. Chấp nhận: trong vòng vài giây đến vài phút, một vài client có thể thấy số tồn kho hơi cũ — miễn là *cuối cùng* không oversell.

Một framework lý tưởng phải cho phép developer **chọn được lớp use case** của mình và áp dụng chiến lược tương ứng *mà không phải đổi code nghiệp vụ*.

### 1.2.3. Các thách thức kỹ thuật cụ thể

Để giải quyết bài toán, framework phải xử lý đồng thời 7 thách thức:

| # | Thách thức | Hậu quả nếu không xử lý |
|---|---|---|
| C1 | **Race condition khi nhiều request cùng giảm tồn kho** | Oversell |
| C2 | **Phân tán transaction qua 2 hệ thống** (DB nội bộ + payment gateway bên ngoài) | Tiền đã trừ nhưng đơn không xác nhận, hoặc ngược lại |
| C3 | **Idempotency** — client có thể gửi cùng request nhiều lần (network retry, F5) | Trừ tiền 2 lần, đặt 2 vé cho 1 ý định |
| C4 | **At-least-once delivery của message broker** — consumer nhận event nhiều lần | Giảm tồn kho 2 lần cho 1 đơn |
| C5 | **Crash giữa các bước** — server tắt giữa "trừ Redis" và "publish event" | Mất event → DB không sync → inconsistency âm thầm |
| C6 | **Tải đỉnh** — vài chục nghìn req/s trong 5 giây | Server sập, request bị reject |
| C7 | **Inconsistency âm thầm** — không có error log, chỉ phát hiện khi đối soát cuối ngày | Mất tiền, mất uy tín, không phát hiện sớm |

---

## 1.3. Mục tiêu nghiên cứu

### 1.3.1. Mục tiêu tổng quát

Thiết kế và hiện thực một **framework Spring Boot mã nguồn mở (HCR — High Concurrency Resource)** giải quyết toàn diện 7 thách thức C1–C7, cho phép developer xây ứng dụng phân phát tài nguyên có giới hạn ở quy mô tải cao bằng cách *kế thừa lớp cha*, *cấu hình YAML*, và *implement vài method nghiệp vụ riêng*, mà không phải tự viết lại các cơ chế concurrency / saga / reconciliation.

### 1.3.2. Mục tiêu cụ thể

Đề tài hướng tới 5 mục tiêu cụ thể, có thể đo lường được:

| Mục tiêu | Mô tả | Tiêu chí thành công |
|---|---|---|
| **MT1** | Cung cấp **3 chiến lược inventory** (Pessimistic / Optimistic / Redis Atomic) đại diện 3 điểm trên đường cong throughput-consistency, có thể đổi qua YAML mà không sửa code | Cùng business code của một sample app phải chạy được với cả 3 chiến lược |
| **MT2** | Đảm bảo **zero-oversell** trên cả 3 chiến lược dưới tải 5000–10000 req/s | Load test k6 với 200 000 request, xác minh tổng vé bán ra ≤ tổng vé khởi tạo |
| **MT3** | Cung cấp **Saga orchestration** (sync và async) để xử lý distributed transaction giữa inventory và payment, tự động compensate khi fail | Đếm số lượng order ở trạng thái treo (PENDING, COMPENSATING) sau load test = 0 |
| **MT4** | Cung cấp **Reconciliation safety net** xử lý 5 case inconsistency, chạy theo lịch với distributed lock | Inject lỗi nhân tạo (mất event, payment timeout, Redis crash) → reconciliation phát hiện và fix trong ≤ 5 phút |
| **MT5** | **Giảm khối lượng code** mà developer phải viết khi xây dựng một use case mới (concert, flash sale, hotel) | So sánh số dòng code giữa: (a) tự viết từ đầu, (b) dùng HCR — kỳ vọng giảm 60–80 % |

### 1.3.3. Câu hỏi nghiên cứu

Đề tài tìm câu trả lời cho 4 câu hỏi:

- **CH1**: Ba chiến lược (Pessimistic Lock, Optimistic Lock, Redis Atomic) cho throughput và độ trễ thực tế là bao nhiêu trên cùng workload? Khi nào nên chọn chiến lược nào?
- **CH2**: Có thể abstract hoá 3 chiến lược này phía sau cùng một interface mà *không* làm hỏng đặc tính riêng của từng chiến lược (ví dụ: zero-DB-hit trong critical path của Redis Atomic) không?
- **CH3**: Saga async (HTTP 202 + EventBus) có gây ra rủi ro inconsistency mới mà saga sync không có? Reconciliation có thể bù đắp được không?
- **CH4**: Tổng chi phí phát triển một ứng dụng phân phát tài nguyên giảm bao nhiêu khi dùng framework so với tự viết?

---

## 1.4. Phạm vi nghiên cứu

### 1.4.1. Trong phạm vi (in-scope)

- **Backend framework Spring Boot 3.2** (Java 17), gồm 12 module Maven, đóng gói thành Spring Boot starter.
- **3 chiến lược inventory** (P1 Pessimistic, P2 Optimistic, P3 Redis Atomic) hoàn chỉnh.
- **Saga sync và async** với compensation tự động.
- **4 adapter EventBus** (In-Memory, Kafka, RabbitMQ, Redis Streams).
- **Gateway pipeline** (validate, idempotency, rate limit, circuit breaker).
- **Reconciliation 5 case** với distributed lock.
- **Observability** qua Micrometer → Prometheus.
- **Sample app** *Concert Ticket Booking* để kiểm chứng framework.
- **Load test k6** mô phỏng workload thực tế (steady, spike) để so sánh 3 chiến lược.

### 1.4.2. Ngoài phạm vi (out-of-scope)

Để tập trung vào bài toán cốt lõi và phù hợp thời lượng đồ án, các phần sau **không** được giải quyết:

- **Frontend / UI**: chỉ có REST API, không có web/mobile UI. *(Không phải mục tiêu nghiên cứu — đã có nhiều giải pháp tốt.)*
- **Authentication / Authorization**: framework không tích hợp JWT/OAuth2. Người dùng được giả định là `requesterId` thuần. *(Tách bạch — không thuộc phạm vi bài toán phân phát tài nguyên.)*
- **Tích hợp gateway thanh toán thật** (VNPay, Stripe, MoMo): chỉ có `MockPaymentGateway` mô phỏng đầy đủ scenario A/B (timeout, lost response). Real gateway được thiết kế trong abstraction (`AbstractPaymentGateway`) để developer dễ tích hợp về sau.
- **Multi-region active-active**: framework giả định triển khai trong một region/cluster. Cross-region replication không được giải quyết.
- **Auto-scaling logic / Kubernetes operator**: chỉ ship JAR + Docker compose, không có k8s operator.
- **Frontend admin tool** cho reconciliation: chỉ expose qua log và Prometheus metrics.

### 1.4.3. Giả thiết

- **Giả thiết H1**: Có một cluster Redis 7+ với tính sẵn sàng cao (sentinel hoặc cluster mode). Trường hợp Redis hỏng hoàn toàn thuộc về trách nhiệm của hệ thống vận hành.
- **Giả thiết H2**: Có một cluster PostgreSQL (hoặc tương đương) hỗ trợ `SELECT … FOR UPDATE` và `@Version` (optimistic locking).
- **Giả thiết H3**: Có một message broker hỗ trợ at-least-once delivery (Kafka / RabbitMQ / Redis Streams). Chế độ at-most-once và exactly-once không được hỗ trợ chính thức.
- **Giả thiết H4**: Workload tải đỉnh kéo dài tối đa vài phút, không phải tải cao liên tục 24/7. *(Nếu liên tục, cần thêm sharding theo resourceId — đề xuất trong "Hướng phát triển".)*

---

## 1.5. Đóng góp chính

Đề tài có 4 đóng góp:

### Đóng góp 1 — Thiết kế khái niệm (Conceptual contribution)

Một **framework đa chiến lược (multi-strategy framework)** trong đó developer chọn lớp use case của mình thông qua một thuộc tính cấu hình duy nhất (`hcr.inventory.strategy`), và toàn bộ kiến trúc bên dưới (saga sync vs async, có cần `SagaStateRepository` hay không, persist DB sync hay async qua consumer) tự động điều chỉnh theo. Đây là điểm khác biệt cốt lõi so với các framework saga đa năng (vốn chỉ cung cấp một mô hình orchestration).

### Đóng góp 2 — Kỹ thuật (Technical contribution)

- **Atomic reservation qua Lua script** trong Redis cho P3, có guard chống `INCR` quá `total` (chống inventory leak khi double-release).
- **Pipeline `FrameworkGateway` 6 bước** kết hợp validate / idempotency / rate limit / circuit breaker, expose dưới dạng method `final` để developer không thể vô tình bỏ qua.
- **Reconciliation 5 case** với distributed lock Redisson — đảm bảo chỉ 1 instance làm việc trong một thời điểm trên cluster nhiều node.
- **Bảng `processed_events` để dedup ở consumer** thay vì điều kiện `WHERE available >= delta` (vốn tạo race nguy hiểm khi consumer retry).

### Đóng góp 3 — Kỹ thuật phần mềm (Software engineering contribution)

- **Reusable Spring Boot starter** đầy đủ auto-configuration: developer chỉ cần thêm dependency `hcr-spring-boot-starter` và override `validateBusinessRules()` + `createOrder()` + `findOrder()` + `saveOrder()` + `buildPaymentRequest()` để có một ứng dụng phân phát tài nguyên hoàn chỉnh.
- **Test support** (`hcr-testing`) cho concurrency testing.
- **Sample app** *concert-ticket* hoàn chỉnh, kèm Docker compose + load test k6.

### Đóng góp 4 — Đánh giá (Evaluation contribution)

- Bộ kết quả benchmark thực nghiệm so sánh 3 chiến lược dưới cùng workload, cùng phần cứng — *bằng chứng số liệu* cho lập luận lý thuyết.
- Phân tích chi phí phát triển (số dòng code, thời gian) khi xây dựng cùng một use case (a) tự viết và (b) dùng HCR.

> `[Lưu ý cho sinh viên: cần làm rõ đóng góp cá nhân vs đóng góp team nếu đây là dự án nhóm. Nếu cá nhân, bỏ chú thích này.]`

---

## 1.6. Phương pháp tiếp cận

Đồ án sử dụng kết hợp ba phương pháp:

### 1.6.1. Nghiên cứu lý thuyết (Theoretical study)

- Khảo sát các pattern trong xử lý concurrency: pessimistic/optimistic locking (Kung & Robinson 1981), CAP theorem (Brewer 2000), Saga pattern (Garcia-Molina & Salem 1987), Idempotency, Circuit Breaker (Nygard 2007), Token Bucket.
- Phân tích các framework saga đương đại (Eventuate Tram, Axon, Camunda 8, Temporal, Apache Seata) — điểm mạnh, điểm yếu so với bài toán đặt ra.
- Tổng hợp thành Chương 2 — Cơ sở lý thuyết.

### 1.6.2. Thiết kế và hiện thực (Design and implementation)

- Phân tích yêu cầu từ 4 use case (concert, flash sale, hotel, hospital slot) → trừu tượng hoá thành interface chung.
- Thiết kế kiến trúc 3 layer (Application / Framework / Infrastructure), trong đó Framework chia thành 12 module Maven có dependency rõ ràng — trình bày trong Chương 3.
- Hiện thực bằng Java 17 + Spring Boot 3.2.5 + Lombok + Redisson + Resilience4j.
- Quy trình *iterative* — sau mỗi module có một sample test để kiểm chứng nhanh.

### 1.6.3. Đánh giá thực nghiệm (Empirical evaluation)

- Triển khai sample app *concert-ticket* trên `[loại máy/cluster cụ thể, ví dụ: 1 instance app + Redis 7 + Postgres 15 + Kafka, máy 16 vCPU 32 GB RAM]`.
- Thiết kế các workload k6: steady-low (1k req/s), steady-high (5k req/s), spike (0 → 10k req/s trong 5s), endurance (1k req/s × 30 phút).
- Đo các chỉ số: throughput, p50/p95/p99 latency, oversell count (kỳ vọng = 0), success rate, retry count.
- So sánh 3 chiến lược, phân tích bottleneck, đưa ra hướng dẫn lựa chọn — trình bày trong Chương 4.
- Đếm số dòng code và số file phải viết khi xây dựng app *flash-sale* sử dụng framework, so sánh với baseline tự viết.

---

## 1.7. Cấu trúc báo cáo

Báo cáo gồm **5 chương**:

| # | Chương | Nội dung chính |
|---|---|---|
| **1** | **Giới thiệu** *(chương này)* | Bối cảnh, phát biểu bài toán, mục tiêu nghiên cứu, phạm vi, đóng góp chính, phương pháp tiếp cận, cấu trúc báo cáo |
| **2** | **Cơ sở lý thuyết** | CAP theorem, Saga pattern, Optimistic / Pessimistic concurrency control, Idempotency, At-least-once delivery, Token Bucket rate limiting, Circuit Breaker — định nghĩa, công thức/thuật toán, và mapping vào HCR Framework |
| **3** | **Kiến trúc đề xuất** | **3.1 Tổng quan hệ thống:** kiến trúc 3 layer, 12 module và dependency graph, end-to-end request flow. <br> **3.2 Chi tiết từng module:** mỗi module (core, eventbus, inventory, payment, saga, gateway, reconciliation, observability) có class diagram, design rationale, capabilities, và quyết định kỹ thuật quan trọng (decision log) |
| **4** | **Phân tích thực nghiệm** | Setup môi trường, sample app *Concert Ticket Booking*, thiết kế load test k6, kết quả 3 chiến lược (throughput / latency / correctness), phân tích bottleneck, kiểm chứng reconciliation qua fault injection, so sánh chi phí phát triển khi dùng HCR vs tự viết |
| **5** | **Kết luận** | Đối chiếu kết quả đạt được vs mục tiêu MT1–MT5, hạn chế của framework, hướng phát triển (sharding theo resourceId, multi-region, tích hợp payment gateway thật, OpenTelemetry, k8s operator) |

---

## 1.8. Tóm tắt nội dung báo cáo

Để giúp người đọc nắm được bức tranh toàn cảnh trước khi đi vào chi tiết, mục này tóm tắt nội dung của **cả 5 chương**.

### Chương 1 — Giới thiệu

Chương này mô tả bối cảnh xã hội và kỹ thuật đã làm bài toán *phân phát tài nguyên có giới hạn dưới tải cao* (zero-oversell at high concurrency) trở nên cấp thiết: từ vé concert quốc tế, flash sale 0 đồng của các hãng hàng không trong nước, đến đặt slot khám bệnh và đăng ký tín chỉ ở các trường đại học. Tác giả phân tích ba dạng hậu quả khi giải quyết sai (oversell, server sập, inconsistency âm thầm) cùng ba khoảng trống của các giải pháp hiện hữu, từ đó đề xuất xây dựng **HCR Framework** — một Spring Boot framework mã nguồn mở. Chương cũng đặt ra 5 mục tiêu cụ thể (MT1–MT5), 4 câu hỏi nghiên cứu (CH1–CH4), xác định rõ phạm vi (in-scope / out-of-scope), giả thiết, và phương pháp tiếp cận kết hợp giữa nghiên cứu lý thuyết, thiết kế và hiện thực, và đánh giá thực nghiệm.

### Chương 2 — Cơ sở lý thuyết

Chương này trình bày nền tảng lý thuyết mà framework xây dựng trên đó. Phần đầu giới thiệu **CAP theorem** (Brewer 2000) và cách định lý này định hình lựa chọn giữa Strong và Eventual consistency cho 3 chiến lược inventory của HCR. Tiếp theo là các kỹ thuật điều khiển truy cập đồng thời: **Pessimistic Concurrency Control** (locking-based, Bernstein 1981) và **Optimistic Concurrency Control** (Kung & Robinson 1981, dùng version-checking + retry), cùng phân tích điểm mạnh/yếu khi áp dụng cho bài toán inventory. Chương tiếp tục với **Saga pattern** (Garcia-Molina & Salem 1987) — cơ chế xử lý long-lived transaction qua chuỗi local transaction + compensating action, và phân biệt hai mô hình triển khai: orchestration vs choreography. Các pattern bổ trợ được trình bày: **Idempotency** (chống tác động lặp khi client / consumer retry), **At-least-once delivery + dedup** (đặc tính chuẩn của message broker và cách xử lý), **Token Bucket** (thuật toán rate limiting công bằng), và **Circuit Breaker** (Nygard 2007 — bảo vệ hệ thống khỏi cascading failure khi dependency suy yếu). Cuối chương, tác giả khảo sát các *related work* — so sánh chức năng của HCR với các framework saga đương đại (Eventuate Tram, Axon, Camunda 8, Temporal, Apache Seata) để làm nổi bật điểm khác biệt: HCR tập trung vào **inventory + saga + reconciliation** dưới một interface duy nhất, thay vì chỉ orchestration tổng quát.

### Chương 3 — Kiến trúc đề xuất

Chương này là phần trung tâm của báo cáo và được chia làm hai phần. **Phần 3.1 — Tổng quan hệ thống:** mô tả kiến trúc 3 layer (Application / Framework / Infrastructure) và 12 module Maven của HCR (8 module nghiệp vụ + 4 module hạ tầng đóng gói). Tác giả trình bày dependency graph giữa các module, end-to-end request flow tiêu biểu (P3 + Async Saga + Kafka) bằng sequence diagram, và chỉ ra cách lựa chọn `hcr.inventory.strategy` ảnh hưởng tự động tới toàn bộ kiến trúc bên dưới. **Phần 3.2 — Chi tiết từng module:** mỗi module (`hcr-core`, `hcr-eventbus`, `hcr-inventory`, `hcr-payment`, `hcr-saga`, `hcr-gateway`, `hcr-reconciliation`, `hcr-observability`) được trình bày theo cấu trúc thống nhất gồm: vai trò trong hệ thống, class diagram, design rationale (tại sao chọn pattern này thay vì pattern khác), capabilities cung cấp cho developer, và những quyết định kỹ thuật then chốt (ví dụ: tại sao P3 phải zero-DB-hit trong critical path; tại sao `release()` của Circuit Breaker không được reject khi OPEN; tại sao tách bảng `processed_events` để dedup thay vì dùng `WHERE available >= delta`). Đặc biệt, chương trình bày chi tiết 3 chiến lược inventory (P1 Pessimistic Lock với `SELECT … FOR UPDATE`, P2 Optimistic Lock với `@Version` và retry, P3 Redis Atomic với Lua script) cùng cơ chế Saga sync (cho P1/P2) và async (cho P3) được abstract sau cùng một template method `AbstractSagaOrchestrator.process()`.

### Chương 4 — Phân tích thực nghiệm

Chương này kiểm chứng các tuyên bố của Chương 3 bằng số liệu thực tế. Tác giả mô tả **setup môi trường** (`[spec phần cứng cụ thể — sinh viên điền sau khi đo]`: máy chạy app + Redis + PostgreSQL + Kafka), **sample app** *Concert Ticket Booking* (gồm `ConcertTicket extends AbstractInventoryEntity`, `TicketOrder extends AbstractOrder`, `TicketBookingOrchestrator extends SynchronousSagaOrchestrator`), và bộ **load test k6** với 4 workload đại diện: steady-low (1k req/s), steady-high (5k req/s), spike (0 → 10k req/s trong 5 giây), và endurance (1k req/s liên tục trong 30 phút). Các chỉ số đo gồm throughput, độ trễ p50/p95/p99, oversell count (phải = 0), success rate, retry count. Kết quả được trình bày dưới dạng bảng và biểu đồ **so sánh trực tiếp 3 chiến lược** trên cùng workload, kèm phân tích bottleneck (P1 bị giới hạn bởi DB lock contention, P2 bị giới hạn bởi tỉ lệ retry tăng cao khi contention nặng, P3 bị giới hạn bởi single-thread Redis). Sau đó, tác giả **kiểm chứng Reconciliation** bằng fault injection: gây mất event giữa Redis DECR và `EventBus.publish()`, gây timeout payment gateway, gây Redis crash recovery — đo thời gian mà reconciliation phát hiện và sửa lỗi. Cuối cùng, chương đo **chi phí phát triển**: số dòng code, số file, thời gian thực hiện khi xây dựng use case *flash-sale* bằng HCR so với tự viết từ đầu — kiểm chứng MT5.

### Chương 5 — Kết luận

Chương này tổng kết những gì đề tài đã làm được và chưa làm được. Phần đầu **đối chiếu kết quả vs mục tiêu MT1–MT5**: framework đã cung cấp đủ 3 chiến lược switchable qua YAML (MT1 ✓), zero-oversell được kiểm chứng dưới tải `[X]` req/s (MT2 ✓ / ✗), số order treo sau load test = 0 (MT3 ✓), reconciliation phát hiện inconsistency trong vòng `[Y]` phút (MT4 ✓), số dòng code giảm `[Z]` % (MT5). Phần tiếp theo nêu **hạn chế của framework**: gap giữa Redis DECR và publish event (đã có reconciliation cứu nhưng có window ≤ 5 phút), batch consumer ACK-trước-flush, hot-key contention chưa giải quyết, và một vài hạn chế khác. Phần cuối đề xuất **hướng phát triển**: (1) sharding theo resourceId để loại bỏ hot-key, (2) multi-region active-active với CRDT inventory, (3) tích hợp gateway thanh toán thật và webhook, (4) tracing chuẩn W3C qua OpenTelemetry, (5) Kubernetes operator để tự động hoá deployment, (6) bảng điều khiển admin cho reconciliation. Đề tài kết thúc với khẳng định: HCR Framework đã chứng minh được **tính khả thi của một framework chuyên biệt cho bài toán phân phát tài nguyên có giới hạn**, và mở ra hướng nghiên cứu cho các framework tiếp theo trong các bài toán đặc trưng của hệ thống phân tán.

---

## Phụ lục — Số liệu / dẫn chứng cần thu thập thêm

> Các con số dưới đây cần kiểm chứng thực tế trước khi đưa vào báo cáo. Hiện tại để placeholder để lúc viết báo cáo bạn điền.

| Dẫn chứng | Nguồn cần tra | Trạng thái |
|---|---|---|
| Thiệt hại thực của Ticketmaster trong sự cố Taylor Swift 2022 | Báo cáo điều trần Quốc hội Mỹ, các bài báo Reuters / NYTimes 2022–2023 | ⚠️ Cần xác minh số liệu cụ thể |
| Quy mô flash sale VietJet 0đ (số vé / số request đỉnh) | PR/báo chí VN, hoặc tham chiếu blog kỹ thuật của VietJet nếu có | ⚠️ Cần xác minh |
| So sánh framework saga: Eventuate Tram, Axon, Camunda 8, Temporal, Seata | Tài liệu chính thức từng framework | ⚠️ Sẽ làm chi tiết trong Chương 2 (cơ sở lý thuyết) hoặc Chương 3 (related work) |
| Spec phần cứng dùng cho load test | Quyết định khi triển khai | ⚠️ Sinh viên điền vào Chương 4 |
| Kết quả load test 3 strategy | Chạy thực tế | ⚠️ Sinh viên cập nhật vào Chương 4 |

---

> **Hết Chương 1.** &nbsp;·&nbsp; Tiếp theo: `02-theoretical-foundation.md` (Cơ sở lý thuyết).
