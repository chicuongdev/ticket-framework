package io.hrc.product.order.controller;

import io.hrc.core.enums.OrderStatus;
import io.hrc.core.exception.FrameworkException;
import io.hrc.core.exception.ValidationException;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.product.order.domain.TicketRequest;
import io.hrc.product.order.repository.TicketOrderRepository;
import io.hrc.product.order.saga.TicketBookingOrchestrator;
import io.hrc.product.shared.dto.PlaceTicketOrderRequest;
import io.hrc.product.shared.dto.TicketOrderResponse;
import io.hrc.saga.repository.SagaStateRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
@Slf4j
public class OrderController {

    private static final String IDEMPOTENCY_KEY_PREFIX = "hcr:idempotency:";
    private static final String CLAIM_IN_FLIGHT = "PROCESSING";
    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final TicketBookingOrchestrator orchestrator;
    private final TicketOrderRepository orderRepository;
    private final SagaStateRepository<TicketOrder> sagaStateRepository;
    private final StringRedisTemplate redis;

    @PostMapping
    public ResponseEntity<?> place(@Valid @RequestBody PlaceTicketOrderRequest body) {
        // Idempotency claim TRƯỚC khi vào saga — chống duplicate request đồng thời.
        // SETNX atomic: chỉ 1 request giành được claim, các request khác cùng key bị reject sớm.
        String idempotencyKey = IDEMPOTENCY_KEY_PREFIX + body.getIdempotencyKey();
        Boolean claimed = redis.opsForValue()
                .setIfAbsent(idempotencyKey, CLAIM_IN_FLIGHT, IDEMPOTENCY_TTL);
        if (Boolean.FALSE.equals(claimed)) {
            return handleDuplicate(idempotencyKey);
        }

        String requestId = UUID.randomUUID().toString();
        try {
            TicketRequest request = new TicketRequest(
                    body.getResourceId(),
                    body.getRequesterId(),
                    body.getQuantity(),
                    body.getIdempotencyKey());
            TicketOrder order = orchestrator.process(request);

            // Reserve fail path: orchestrator KHÔNG throw — set status CANCELLED rồi return.
            // Controller phải check để trả 422 thay vì 202, không thì client tưởng đặt thành công.
            if (order.getStatus() == OrderStatus.CANCELLED) {
                // Reserve fail → release claim để client retry với key khác/cùng key sau khi fix
                redis.delete(idempotencyKey);
                String reason = order.getFailureReason() != null
                        ? order.getFailureReason().name() : "RESERVE_FAILED";
                return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                        "error", reason, "orderId", order.getOrderId(),
                        "message", "Reserve failed: " + reason));
            }

            // Reserve OK → ghi orderId vào claim để duplicate request sau này tra cứu được
            redis.opsForValue().set(idempotencyKey, order.getOrderId(), IDEMPOTENCY_TTL);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(order));

        } catch (ValidationException e) {
            redis.delete(idempotencyKey);
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "VALIDATION", "message", e.getMessage()));
        } catch (FrameworkException e) {
            redis.delete(idempotencyKey);
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                    "error", e.getReason().name(), "message", e.getMessage()));
        } catch (Exception e) {
            // Internal error: log full detail server-side, trả message generic + requestId cho client
            redis.delete(idempotencyKey);
            log.error("[ms-order] place failed (requestId={}): {}", requestId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "SYSTEM_ERROR",
                    "requestId", requestId,
                    "message", "Internal server error. Please retry or contact support with requestId."));
        }
    }

    /**
     * Duplicate request handling:
     * - Nếu claim còn ở "PROCESSING" → request đang xử lý, trả 409.
     * - Nếu claim đã chứa orderId → trả status hiện tại của order (giống GET /orders/{id}).
     */
    private ResponseEntity<?> handleDuplicate(String idempotencyKey) {
        String existing = redis.opsForValue().get(idempotencyKey);
        if (existing == null || CLAIM_IN_FLIGHT.equals(existing)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "DUPLICATE_IN_FLIGHT",
                    "message", "Same idempotencyKey is being processed. Retry after a moment."));
        }
        return get(existing);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<?> get(@PathVariable String orderId) {
        // Async P3 flow: order chỉ vào DB SAU khi payment thành công.
        // Trước thời điểm đó (vài trăm ms - vài giây), order sống trong Redis saga state.
        // Fallback Redis nếu DB miss để client GET ngay sau POST không bị 404.
        var fromDb = orderRepository.findByOrderId(orderId);
        if (fromDb.isPresent()) {
            return ResponseEntity.ok(toResponse(fromDb.get()));
        }
        var fromSaga = sagaStateRepository.findByOrderId(orderId);
        if (fromSaga.isPresent()) {
            return ResponseEntity.ok(toResponse(fromSaga.get().getOrder()));
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "NOT_FOUND", "orderId", orderId));
    }

    private TicketOrderResponse toResponse(TicketOrder order) {
        return new TicketOrderResponse(
                order.getOrderId(),
                order.getResourceId(),
                order.getRequesterId(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getTotalAmount(),
                order.getCurrency(),
                order.getCreatedAt(),
                order.getExpiresAt(),
                order.getFailureReason() != null ? order.getFailureReason().name() : null);
    }
}
