package io.hrc.sample.controller;

import io.hrc.core.exception.FrameworkException;
import io.hrc.sample.domain.TicketOrder;
import io.hrc.sample.domain.TicketRequest;
import io.hrc.sample.service.TicketBookingOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * REST API cho concert ticket booking.
 *
 * <pre>
 * POST /tickets/book        — đặt vé
 * GET  /tickets/{orderId}   — kiểm tra trạng thái order
 * </pre>
 */
@RestController
@RequestMapping("/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketBookingOrchestrator orchestrator;

    /**
     * Đặt vé concert.
     *
     * <p>Request body:
     * <pre>{@code
     * {
     *   "resourceId": "concert-2026-06-15",
     *   "requesterId": "user-123",
     *   "quantity": 2,
     *   "buyerEmail": "buyer@example.com",
     *   "idempotencyKey": "uuid-from-client"  // optional, sinh tự động nếu thiếu
     * }
     * }</pre>
     */
    @PostMapping("/book")
    public ResponseEntity<?> bookTicket(@RequestBody BookTicketRequest body) {
        try {
            String idempotencyKey = body.idempotencyKey() != null
                    ? body.idempotencyKey()
                    : UUID.randomUUID().toString();

            TicketRequest request = new TicketRequest(
                    body.resourceId(),
                    body.requesterId(),
                    body.quantity(),
                    idempotencyKey,
                    body.buyerEmail());

            TicketOrder order = orchestrator.process(request);

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "orderId", order.getOrderId(),
                    "status", order.getStatus().name(),
                    "resourceId", order.getResourceId(),
                    "quantity", order.getQuantity(),
                    "totalAmount", order.getTotalAmount()
            ));

        } catch (FrameworkException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", e.getReason().name(),
                    "message", e.getMessage()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "SYSTEM_ERROR",
                    "message", e.getMessage()
            ));
        }
    }

    /** Request body record cho đặt vé. */
    public record BookTicketRequest(
            String resourceId,
            String requesterId,
            int quantity,
            String buyerEmail,
            String idempotencyKey) {}
}
