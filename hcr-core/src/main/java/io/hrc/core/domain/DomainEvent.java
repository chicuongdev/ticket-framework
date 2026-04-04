package io.hrc.core.domain;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Base class cho TẤT CẢ event được publish lên Event Bus.
 * Mọi event trong framework đều extend từ class này.
 *
 * <p><b>correlationId</b> là field quan trọng nhất cho distributed tracing:
 * <ul>
 *   <li>Gateway sinh correlationId mới nếu request không có {@code X-Correlation-ID} header.</li>
 *   <li>Mọi event từ cùng 1 request đều mang cùng correlationId.</li>
 *   <li>Framework đặt correlationId vào MDC → developer grep log theo ID này
 *       để trace toàn bộ luồng xử lý của 1 request.</li>
 *   <li>correlationId được forward vào PaymentGateway request header →
 *       correlate với log phía gateway bên thứ 3.</li>
 * </ul>
 *
 * <p><b>retryCount:</b> Framework tự tăng mỗi khi consumer không ack và event
 * được deliver lại. Developer không cần tự quản lý field này.
 */
@Getter
@Setter
public abstract class DomainEvent {

    /** UUID duy nhất toàn hệ thống, tự sinh khi tạo event. */
    private final String eventId = UUID.randomUUID().toString();

    /**
     * Tên class của event — tự động set từ class name.
     * Dùng để routing và logging.
     */
    private final String eventType = this.getClass().getSimpleName();

    /** ID tài nguyên liên quan đến event này. */
    private String resourceId;

    /** ID order liên quan đến event này (null nếu event không gắn với order cụ thể). */
    private String orderId;

    /** Thời điểm event xảy ra trong hệ thống. */
    private final Instant occurredAt = Instant.now();

    /**
     * Số lần event đã được deliver nhưng consumer chưa ack.
     * Framework tự tăng — developer không set trực tiếp.
     */
    private int retryCount = 0;

    /**
     * ID để trace 1 request xuyên suốt toàn hệ thống.
     * Framework tự propagate từ Gateway → Saga → Event Bus → Consumer.
     */
    private String correlationId;

    protected DomainEvent(String resourceId, String orderId, String correlationId) {
        this.resourceId = resourceId;
        this.orderId = orderId;
        this.correlationId = correlationId;
    }

    protected DomainEvent() {
    }

    /** Framework gọi khi re-deliver event do consumer không ack. */
    public void incrementRetryCount() {
        this.retryCount++;
    }
}
