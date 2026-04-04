package io.hrc.inventory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Idempotency guard cho EventBus consumer.
 *
 * <p>Bảng {@code hcr_processed_events} lưu {@code eventId} của mọi event
 * đã được xử lý thành công. Khi consumer nhận event trùng eventId
 * (at-least-once redeliver), INSERT bị lỗi duplicate key → skip UPDATE, ACK ngay.
 *
 * <p><b>Tại sao cần?</b> {@code WHERE available >= delta} trong UPDATE chỉ tránh trừ âm,
 * KHÔNG tránh trừ 2 lần. Ví dụ: available=100, reserve 2 → lần 1: 98, redeliver lần 2: 96.
 * Idempotency thật sự PHẢI check eventId.
 *
 * <p>INSERT ProcessedEvent và UPDATE available nằm trong cùng 1 transaction
 * → atomic: hoặc cả 2 thành công, hoặc cả 2 rollback.
 */
@Entity
@Table(name = "hcr_processed_events")
@Getter
@NoArgsConstructor
public class ProcessedEvent {

    @Id
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent(String eventId, String eventType) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = Instant.now();
    }
}
