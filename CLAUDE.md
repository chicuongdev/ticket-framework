# HCR Framework — Context cho Claude Code

> File nay duoc doc tu dong moi session. KHONG CAN scan toan bo project.
> Chi tiet tung module: doc `docs/PROGRESS.md` va `{module}/GUIDE.md`.
> Thiet ke chi tiet: doc `docs/framework_design.md`.

## Du an la gi?

HCR (High Concurrency Resource) — Spring Boot framework cho **phan phat tai nguyen co gioi han duoi tai cao** (ve concert, flash sale, phong khach san, slot kham benh). Bai toan cot loi: **zero oversell** khi nhieu nguoi dat dong thoi.

## Stack & Build

- **Java 17**, Spring Boot 3.2.5, Lombok, Redisson, Resilience4j
- Maven multi-module (12 modules): `mvn compile` tai root
- Package goc: `io.hrc.*`

## Cau truc module & tien do

```
✅ hcr-core          — Domain model chung (enums, abstract classes, exceptions, result objects)
✅ hcr-eventbus      — EventBus abstraction (Kafka/RabbitMQ/Redis Streams/InMemory)
✅ hcr-inventory     — 3 inventory strategies (P1/P2/P3) + decorators + persistence consumers
✅ hcr-payment       — Payment gateway abstraction + timeout handler + mock gateway
🔲 hcr-saga          — Saga orchestration (sync + async) ← TIEP THEO
🔲 hcr-gateway       — HTTP entry point, idempotency, rate limiting
🔲 hcr-reconciliation — Redis vs DB reconciliation, expired order cleanup
🔲 hcr-observability — Metrics (Micrometer)
🔲 hcr-testing       — Test support utilities
🔲 hcr-autoconfigure — Spring Boot auto-configuration
🔲 hcr-spring-boot-starter — Starter POM
🔲 hcr-sample        — Demo app concert ticket
```

Dependency flow:
```
core → inventory (can eventbus), eventbus, payment
       → saga (can ca 4 module tren)
         → gateway, reconciliation
           → observability → testing → autoconfigure → starter → sample
```

## 3 Inventory Strategies — Kien thuc cot loi

| | P1 Pessimistic | P2 Optimistic | P3 Redis Atomic |
|--|:-:|:-:|:-:|
| Co che | SELECT FOR UPDATE | @Version + retry | Lua script DECRBY |
| Throughput | ~1,000 req/s | 1,000-5,000 req/s | 5,000-10,000 req/s |
| Consistency | Strong (0ms) | Strong (0ms) | Eventual (<1s / ≤5min worst) |
| Source of truth | PostgreSQL | PostgreSQL | Redis |
| DB trong critical path? | Co | Co | **Khong** (v3) |

### P3 Redis key layout
```
hcr:inventory:{resourceId}           — available quantity (source of truth)
hcr:inventory:total:{resourceId}     — total quantity
hcr:inventory:threshold:{resourceId} — lowStockThreshold (cached from DB khi initialize())
```

### P3 DB sync — 2 mode (configurable)
```yaml
hcr.inventory.persistence.mode: single | batch   # default: single
hcr.inventory.persistence.batch-size: 500
hcr.inventory.persistence.flush-interval-ms: 1000
```
- **SINGLE**: `InventoryPersistenceConsumer` — 1 event = 1 transaction (INSERT dedup + UPDATE available)
- **BATCH**: `BatchInventoryPersistenceConsumer` — gom events theo resourceId, flush 1 transaction. Fallback sang single khi duplicate eventId.

## Saga flow

**Sync (P1/P2):** Validate → Idempotency → Reserve(DB) → Charge(payment) → Confirm → HTTP 201
**Async (P3):** Validate → Idempotency → Reserve(Redis) → Publish event → HTTP 202. Payment + confirm qua EventBus consumers.

Reserve va payment la **2 transaction rieng biet** — DB khong bi lock suot qua trinh thanh toan.

## Quy uoc code QUAN TRONG

1. **TransactionTemplate**, KHONG dung `@Transactional` trong strategies (vi khong phai Spring bean, Factory tao bang `new`)
2. **P2 phai tao transaction moi moi retry** (Hibernate cache version cu trong session)
3. **P3 critical path = zero DB hit** — chi Redis. DB chi duoc access async qua EventBus consumer
4. **Idempotency qua eventId** (bang `hcr_processed_events`), KHONG phai `WHERE available >= delta`
5. **CB release() khong reject khi OPEN** — tranh inventory leak
6. **reserveBatch() sort keys alphabet** (P1) — chong deadlock
7. **Moi module co GUIDE.md** — huong dan thu tu doc code

## Cac file quan trong nhat (khi can doc)

| Can tim gi? | Doc file nao |
|-------------|-------------|
| Tien do + quyet dinh thiet ke | `docs/PROGRESS.md` |
| Thiet ke chi tiet toan framework | `docs/framework_design.md` |
| P1 implementation | `hcr-inventory/src/.../strategy/pessimistic/PessimisticLockStrategy.java` |
| P2 implementation | `hcr-inventory/src/.../strategy/optimistic/OptimisticLockStrategy.java` |
| P3 implementation | `hcr-inventory/src/.../strategy/redis/RedisAtomicStrategy.java` |
| Lua scripts (P3) | `hcr-inventory/src/main/resources/lua/inventory_reserve.lua`, `inventory_release.lua` |
| DB sync consumer (single) | `hcr-inventory/src/.../persistence/InventoryPersistenceConsumer.java` |
| DB sync consumer (batch) | `hcr-inventory/src/.../persistence/BatchInventoryPersistenceConsumer.java` |
| EventBus interface | `hcr-eventbus/src/.../EventBus.java` |
| Payment gateway | `hcr-payment/src/.../gateway/PaymentGateway.java`, `AbstractPaymentGateway.java` |
| Timeout handler | `hcr-payment/src/.../handler/TimeoutHandler.java` |
| Saga orchestrator | `hcr-saga/src/.../orchestrator/AbstractSagaOrchestrator.java` |
| Auto-config | `hcr-autoconfigure/src/.../HcrAutoConfiguration.java` |

## Known limitations (chap nhan)

- **P3 gap:** Giua Redis DECR thanh cong va EventBus.publish() — neu crash o giua, event mat. Reconciliation fix ≤ 5 phut.
- **Batch consumer ACK truoc flush** — neu crash giua ACK va flush, data loss. Reconciliation fix.
- **Cac module 🔲 chua implement** — chi co stub class voi Javadoc mo ta thiet ke.
