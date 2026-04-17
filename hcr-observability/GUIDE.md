# hcr-observability — Hướng dẫn đọc code

## Vai trò

Cung cấp Micrometer metrics cho toàn bộ framework. Developer thêm một dependency,
framework tự track mọi thứ — không cần viết metrics riêng.

## Thứ tự đọc

1. **`FrameworkMetrics`** — interface tổng hợp. Extends `InventoryMetrics` (hcr-inventory) +
   `ReconciliationMetrics` (hcr-reconciliation) + thêm 4 nhóm mới.
   27 methods tổng cộng, chia theo domain.

2. **`MicrometerFrameworkMetrics`** — implementation duy nhất.
   Mỗi method tạo/reuse Micrometer meter (Counter/Timer/Gauge/DistributionSummary).

3. **`metrics/\*MetricsCollector`** — tài liệu tham chiếu.
   Liệt kê metric name + tag cho từng domain. Không có logic, chỉ Javadoc.

4. **`grafana/hcr-dashboard.json`** — dashboard template. Import vào Grafana là xong.

## Thiết kế quan trọng

### FrameworkMetrics extends InventoryMetrics + ReconciliationMetrics

Cho phép inject một bean `MicrometerFrameworkMetrics` duy nhất vào khắp nơi:

```java
// Trong hcr-inventory strategy factory:
InventoryMetrics metrics = ...;  // ← inject MicrometerFrameworkMetrics bean

// Trong hcr-reconciliation:
ReconciliationMetrics metrics = ...;  // ← cùng bean đó

// Cả hai đều record vào cùng MeterRegistry → Prometheus thấy đầy đủ metric
```

### Gauge pattern cho inventory available

Gauge yêu cầu tham chiếu đến object lưu state — không gọi được như Counter/Timer.
`MicrometerFrameworkMetrics` dùng `ConcurrentHashMap<resourceId, AtomicLong>`:

```java
// Lần đầu: tạo AtomicLong + đăng ký Gauge với Micrometer
// Lần sau: chỉ set giá trị AtomicLong
// Thread-safe: computeIfAbsent đảm bảo không đăng ký 2 lần
```

### Metric naming convention

```
hcr_<domain>_<action>_<unit>
  domain: reservation, inventory, saga, payment, reconciliation, event, request
  unit  : total (counter), ms (timer/histogram), (none cho gauge/summary)
```

### Tag naming convention

`snake_case` — ví dụ: `resource_id`, `event_type`, `error_code`.
Không dùng camelCase để tương thích với Prometheus label convention.

## Cách inject vào các module khác

```java
// Auto-configured bởi HcrAutoConfiguration (hcr-autoconfigure):
@Bean
public MicrometerFrameworkMetrics frameworkMetrics(MeterRegistry registry) {
    return new MicrometerFrameworkMetrics(registry);
}

// Strategy factory nhận InventoryMetrics:
InventoryStrategyFactory.create(entityManager, entityClass, eventBus, frameworkMetrics);

// Reconciliation service nhận ReconciliationMetrics:
new MyReconciliationService(..., frameworkMetrics);
```

## Prometheus queries quan trọng

```promql
# Throughput
rate(hcr_request_received_total[1m])

# P99 latency
histogram_quantile(0.99, rate(hcr_request_duration_ms_bucket[1m]))

# Oversell prevented (quan trọng nhất — luôn > 0 dưới tải cao)
sum(hcr_oversell_prevented_total)

# Payment success rate
100 * rate(hcr_payment_duration_ms_count{status="success"}[5m])
    / rate(hcr_payment_attempts_total[5m])

# Inventory mismatch (P3 only — cần Reconciliation fix)
rate(hcr_inventory_mismatch_total[5m])
```

## Files

```
hcr-observability/
├── src/main/java/io/hrc/observability/
│   ├── FrameworkMetrics.java                  ← interface (27 methods + NO_OP)
│   ├── micrometer/
│   │   └── MicrometerFrameworkMetrics.java    ← Micrometer implementation
│   └── metrics/
│       ├── InventoryMetricsCollector.java     ← tài liệu metric names
│       ├── SagaMetricsCollector.java
│       ├── PaymentMetricsCollector.java
│       ├── ReconciliationMetricsCollector.java
│       ├── EventBusMetricsCollector.java
│       └── GatewayMetricsCollector.java
└── src/main/resources/grafana/
    └── hcr-dashboard.json                     ← Grafana dashboard template
```
