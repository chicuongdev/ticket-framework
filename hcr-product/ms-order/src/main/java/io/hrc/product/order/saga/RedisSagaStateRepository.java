package io.hrc.product.order.saga;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hrc.core.enums.OrderStatus;
import io.hrc.product.order.domain.TicketOrder;
import io.hrc.saga.context.SagaContext;
import io.hrc.saga.repository.SagaStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Lưu SagaContext vào Redis để survive crash giữa reserve và payment confirm.
 *
 * <p>Key layout:
 * <pre>
 * hcr:saga:state:{orderId}    — JSON serialize SagaContext, TTL = 1h
 * hcr:saga:by-status:{status} — Set chứa orderId — index để findByStatus
 * </pre>
 *
 * <p>TTL 1h: dài hơn reservation timeout (5 phút) nhiều lần để reconciliation
 * có thời gian recover. Sau 1h, saga state coi như mất hẳn.
 */
@Repository
@Slf4j
public class RedisSagaStateRepository implements SagaStateRepository<TicketOrder> {

    private static final String KEY_PREFIX = "hcr:saga:state:";
    private static final String INDEX_PREFIX = "hcr:saga:by-status:";
    private static final Duration TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RedisSagaStateRepository(StringRedisTemplate redis,
                                     @Qualifier("hcrEventObjectMapper") ObjectMapper mapper) {
        this.redis = redis;
        // AbstractOrder có 3 boolean getter computed (isTerminal/isPending/isExpired) —
        // Jackson serialize ra JSON nhưng deserialize không có setter → throw.
        // Local copy của mapper với FAIL_ON_UNKNOWN_PROPERTIES=false để tolerate.
        this.mapper = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public void save(SagaContext<TicketOrder> context) {
        try {
            String orderId = context.getOrder().getOrderId();
            OrderStatus status = context.getOrder().getStatus();

            String json = mapper.writeValueAsString(context);
            redis.opsForValue().set(KEY_PREFIX + orderId, json, TTL);
            redis.opsForSet().add(INDEX_PREFIX + status.name(), orderId);
            redis.expire(INDEX_PREFIX + status.name(), TTL);

            log.debug("[SagaState] Saved: orderId={}, status={}", orderId, status);
        } catch (Exception e) {
            log.error("[SagaState] Failed to save: orderId={}, error={}",
                    context.getOrder().getOrderId(), e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Optional<SagaContext<TicketOrder>> findByOrderId(String orderId) {
        String json = redis.opsForValue().get(KEY_PREFIX + orderId);
        if (json == null) {
            return Optional.empty();
        }
        try {
            SagaContext<TicketOrder> ctx = mapper.readValue(
                    json, mapper.getTypeFactory().constructParametricType(SagaContext.class, TicketOrder.class));
            return Optional.of(ctx);
        } catch (Exception e) {
            log.error("[SagaState] Deserialize failed: orderId={}, error={}", orderId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public void delete(String orderId) {
        Optional<SagaContext<TicketOrder>> ctx = findByOrderId(orderId);
        ctx.ifPresent(c -> redis.opsForSet()
                .remove(INDEX_PREFIX + c.getOrder().getStatus().name(), orderId));
        redis.delete(KEY_PREFIX + orderId);
        log.debug("[SagaState] Deleted: orderId={}", orderId);
    }

    @Override
    public List<SagaContext<TicketOrder>> findByStatus(OrderStatus status) {
        Set<String> orderIds = redis.opsForSet().members(INDEX_PREFIX + status.name());
        if (orderIds == null || orderIds.isEmpty()) {
            return Collections.emptyList();
        }
        return orderIds.stream()
                .map(this::findByOrderId)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }
}
