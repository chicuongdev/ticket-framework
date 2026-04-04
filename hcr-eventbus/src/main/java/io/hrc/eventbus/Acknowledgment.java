package io.hrc.eventbus;

/**
 * Abstraction cho việc xác nhận xử lý event — thay thế cho broker-specific APIs.
 *
 * <p>Mỗi adapter implement interface này theo cơ chế riêng:
 * <table border="1">
 * <tr><th>Method</th><th>Kafka</th><th>RabbitMQ</th><th>Redis Streams</th><th>InMemory</th></tr>
 * <tr><td>acknowledge()</td><td>commitSync()</td><td>basicAck()</td><td>XACK</td><td>no-op</td></tr>
 * <tr><td>reject()</td><td>seek to offset</td><td>basicNack(requeue=true)</td><td>không XACK</td><td>retry</td></tr>
 * <tr><td>reject(false)</td><td>seek to offset</td><td>basicNack(requeue=false)</td><td>XACK + dead letter</td><td>dead letter</td></tr>
 * </table>
 *
 * <p><b>Quy tắc:</b> Mỗi event delivery PHẢI được acknowledge hoặc reject đúng 1 lần.
 * Không acknowledge → framework sẽ re-deliver sau timeout.
 */
public interface Acknowledgment {

    /**
     * Xác nhận đã xử lý thành công. Framework sẽ không deliver event này lại.
     *
     * <p>Phải gọi SAU KHI side effects đã được persist — không phải trước.
     */
    void acknowledge();

    /**
     * Từ chối event, yêu cầu retry. Framework sẽ deliver lại event này.
     * Dùng cho transient error (network timeout, DB tạm unavailable...).
     *
     * <p>Tương đương {@code reject(true)}.
     */
    void reject();

    /**
     * Từ chối event với lựa chọn có requeue hay không.
     *
     * @param requeue {@code true} = retry (requeue), {@code false} = dead letter (không retry nữa)
     */
    void reject(boolean requeue);
}
