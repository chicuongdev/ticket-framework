package io.hrc.eventbus;

import io.hrc.core.domain.DomainEvent;

/**
 * Developer implement interface này để xử lý event từ Event Bus.
 *
 * <p><b>QUAN TRỌNG — Idempotency:</b> Vì framework cam kết at-least-once delivery,
 * {@link #handle(DomainEvent, Acknowledgment)} CÓ THỂ được gọi nhiều hơn 1 lần
 * cho cùng 1 event. Implementation PHẢI idempotent — xử lý cùng 1 event 2 lần
 * cho kết quả giống nhau (không duplicate side effects).
 *
 * <p><b>Cách dùng Acknowledgment:</b>
 * <ul>
 *   <li>Gọi {@code ack.acknowledge()} SAU KHI đã xử lý xong thành công.</li>
 *   <li>Gọi {@code ack.reject()} nếu muốn retry (transient error).</li>
 *   <li>Gọi {@code ack.reject(false)} nếu muốn dead letter (permanent error).</li>
 * </ul>
 *
 * <p><b>Ví dụ implement:</b>
 * <pre>{@code
 * public class PaymentConsumer implements EventHandler<OrderCreatedEvent> {
 *     @Override
 *     public void handle(OrderCreatedEvent event, Acknowledgment ack) {
 *         try {
 *             paymentService.charge(event.getOrderId());
 *             ack.acknowledge();
 *         } catch (TransientException e) {
 *             ack.reject(); // retry
 *         } catch (PermanentException e) {
 *             ack.reject(false); // dead letter
 *         }
 *     }
 *
 *     @Override
 *     public void onDeadLetter(OrderCreatedEvent event, Throwable cause) {
 *         alertService.sendAlert("Payment permanently failed: " + event.getOrderId());
 *     }
 * }
 * }</pre>
 *
 * @param <T> loại event mà handler này xử lý
 */
public interface EventHandler<T extends DomainEvent> {

    /**
     * Xử lý event. Phải gọi một trong các method của {@code ack} sau khi xử lý.
     *
     * @param event event cần xử lý
     * @param ack   acknowledgment để báo framework kết quả xử lý
     */
    void handle(T event, Acknowledgment ack);

    /**
     * Gọi khi event fail quá số lần retry tối đa — event đã vào Dead Letter Queue.
     * Dùng để alert, log, hoặc thực hiện manual compensation.
     *
     * <p>Default implementation chỉ log — override khi cần xử lý đặc biệt.
     *
     * @param event event đã fail
     * @param cause exception gây ra failure cuối cùng
     */
    default void onDeadLetter(T event, Throwable cause) {
        // Default: no-op. Developer override để alert hoặc compensate.
    }
}
