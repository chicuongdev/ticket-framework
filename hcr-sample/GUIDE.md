# hcr-sample — Hướng dẫn đọc code

## Mục đích
Demo app: **Concert Ticket Booking System** — minh họa cách developer dùng HCR Framework
để build một hệ thống đặt vé có tải cao với zero oversell.

## Thứ tự đọc code

1. **`SampleApplication.java`** — Entry point. `@EnableHighConcurrencyResource` kích hoạt framework.

2. **`domain/ConcertTicket.java`** — Entity tài nguyên. Extends `AbstractInventoryEntity` để
   framework quản lý inventory trực tiếp. Thêm: concertName, venue, eventDate, pricePerTicket.

3. **`domain/TicketOrder.java`** — Entity đơn hàng. Extends `AbstractOrder`. Thêm: totalAmount, buyerEmail.

4. **`domain/TicketRequest.java`** — Request từ client. Extends `OrderRequest`.
   Override `validateRequest()` để thêm business rule (max 4 vé, email hợp lệ).

5. **`repository/TicketOrderRepository.java`** — JPA repository cho TicketOrder.

6. **`service/TicketBookingOrchestrator.java`** — **File quan trọng nhất.**
   Extends `SynchronousSagaOrchestrator`. Implement 6 abstract method:
   - `createOrder()` — tạo TicketOrder từ TicketRequest
   - `findOrder()` — load từ DB
   - `saveOrder()` — persist xuống DB
   - `buildPaymentRequest()` — tạo PaymentRequest (transactionId=orderId, amount=total)
   - `onConfirmed()` — callback sau confirm (gửi email, notification)
   - `onCancelled()` — callback sau cancel

7. **`controller/TicketController.java`** — REST API.
   - `POST /tickets/book` → process(TicketRequest) → HTTP 201
   - Exception handling: FrameworkException → HTTP 422

## application.yml

```yaml
hcr:
  inventory:
    strategy: pessimistic-lock   # P1 — không cần Redis
  saga:
    mode: sync                   # trả kết quả ngay trong HTTP response
  event-bus:
    type: in-memory              # không cần Kafka/RabbitMQ
```

## Luồng xử lý một request đặt vé

```
POST /tickets/book
  → TicketController.bookTicket()
  → TicketBookingOrchestrator.process(TicketRequest)
  → Framework: validate → createOrder (PENDING) → reserve inventory
  → Framework: charge payment → confirm (CONFIRMED)
  → HTTP 201 { orderId, status: CONFIRMED, totalAmount }
```

## Cách test

```bash
curl -X POST http://localhost:8080/tickets/book \
  -H "Content-Type: application/json" \
  -d '{
    "resourceId": "concert-2026-06-15",
    "requesterId": "user-001",
    "quantity": 2,
    "buyerEmail": "buyer@example.com"
  }'
```
