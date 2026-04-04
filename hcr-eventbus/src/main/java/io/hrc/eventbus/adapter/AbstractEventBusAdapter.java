package io.hrc.eventbus.adapter;

import io.hrc.core.domain.DomainEvent;
import io.hrc.eventbus.Acknowledgment;
import io.hrc.eventbus.EventBus;
import io.hrc.eventbus.EventBusCapabilities;
import io.hrc.eventbus.EventDestination;
import io.hrc.eventbus.EventHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base class cho tất cả EventBus adapter.
 *
 * <p>Cung cấp:
 * <ul>
 *   <li>Handler registry — {@code ConcurrentHashMap<eventType, List<handler>>}</li>
 *   <li>{@link #publishBatch(List)} default (gọi {@link #publish(DomainEvent)} từng cái)</li>
 *   <li>{@link #unsubscribe(Class, EventHandler)} default implementation</li>
 *   <li>{@link #dispatch(DomainEvent, Acknowledgment)} — gọi tất cả handlers đã đăng ký</li>
 *   <li>Capability mismatch warning khi subscribe</li>
 * </ul>
 *
 * <p>Subclass cần implement:
 * <ul>
 *   <li>{@link #publish(DomainEvent)}</li>
 *   <li>{@link #publish(DomainEvent, EventDestination)}</li>
 *   <li>{@link #publishIdempotent(DomainEvent, String)}</li>
 *   <li>{@link #getCapabilities()}</li>
 * </ul>
 */
@Slf4j
public abstract class AbstractEventBusAdapter implements EventBus {

    /**
     * Registry: eventType simple name → list of handlers.
     */
    protected final Map<String, List<EventHandler<DomainEvent>>> handlerRegistry =
        new ConcurrentHashMap<>();

    @Override
    public void publishBatch(List<? extends DomainEvent> events) {
        for (DomainEvent event : events) {
            publish(event);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DomainEvent> void subscribe(Class<T> eventType, EventHandler<T> handler) {
        String key = eventType.getSimpleName();
        handlerRegistry.computeIfAbsent(key, k -> new ArrayList<>())
            .add((EventHandler<DomainEvent>) handler);
        log.debug("[EventBus] Subscribed: handler={} → event={}", handler.getClass().getSimpleName(), key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends DomainEvent> void unsubscribe(Class<T> eventType, EventHandler<T> handler) {
        String key = eventType.getSimpleName();
        List<EventHandler<DomainEvent>> handlers = handlerRegistry.get(key);
        if (handlers != null) {
            handlers.remove(handler);
            log.debug("[EventBus] Unsubscribed: handler={} from event={}", handler.getClass().getSimpleName(), key);
        }
    }

    /**
     * Dispatch event đến tất cả handlers đã đăng ký cho event type này.
     * Gọi bởi subclass sau khi nhận event từ broker.
     *
     * @param event event cần dispatch
     * @param ack   acknowledgment object từ broker
     */
    protected void dispatch(DomainEvent event, Acknowledgment ack) {
        String key = event.getClass().getSimpleName();
        List<EventHandler<DomainEvent>> handlers = handlerRegistry.get(key);

        if (handlers == null || handlers.isEmpty()) {
            log.warn("[EventBus] No handlers registered for event type: {}. Auto-acknowledging.", key);
            ack.acknowledge();
            return;
        }

        for (EventHandler<DomainEvent> handler : handlers) {
            try {
                handler.handle(event, ack);
            } catch (Exception e) {
                log.error("[EventBus] Handler {} threw unexpected exception for event {}: {}",
                    handler.getClass().getSimpleName(), key, e.getMessage(), e);
                // Không re-throw — các handler khác vẫn được gọi
            }
        }
    }

    /**
     * Log warning nếu capabilities không thỏa mãn yêu cầu.
     * Gọi bởi subclass khi cần check capability trước khi thực hiện operation.
     *
     * @param capability  tên capability đang check
     * @param supported   adapter có support không
     * @param context     mô tả ngữ cảnh (để log warning)
     */
    protected void warnIfNotSupported(String capability, boolean supported, String context) {
        if (!supported) {
            log.warn("[HCR-WARN] Capability mismatch detected:\n" +
                     "  Adapter: {}\n" +
                     "  Required: {} = true\n" +
                     "  Actual:   {} = false\n" +
                     "  Context:  {}",
                getClass().getSimpleName(), capability, capability, context);
        }
    }
}
