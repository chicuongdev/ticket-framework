package io.hrc.eventbus;

import io.hrc.core.domain.DomainEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry map từ {@code DomainEvent.getEventType()} (simple class name)
 * sang {@code Class<? extends DomainEvent>} để Kafka consumer deserialize
 * được event lên đúng kiểu concrete.
 *
 * <p><b>Cách dùng:</b> Construct với list base package cần scan. Khi init,
 * registry dùng Spring {@code ClassPathScanningCandidateComponentProvider}
 * để tìm tất cả class concrete extend {@link DomainEvent} và cache lại.
 *
 * <p><b>Vì sao cần:</b> Kafka consumer chỉ có payload JSON + header
 * {@code X-Event-Type=ResourceReservedEvent}. Không có reflection nào đi
 * từ simple name về Class được (simple name có thể trùng ở package khác)
 * → cần registry cache sẵn khi startup.
 */
@Slf4j
public class EventTypeRegistry {

    private final Map<String, Class<? extends DomainEvent>> registry;

    /**
     * Scan các package cho sẵn để tìm concrete {@link DomainEvent}.
     *
     * @param basePackages ví dụ {@code ["io.hrc"]}
     */
    public EventTypeRegistry(String... basePackages) {
        this.registry = scan(basePackages);
        log.info("[EventTypeRegistry] Registered {} event types: {}",
                registry.size(), registry.keySet());
    }

    /**
     * Lookup class theo simple name.
     *
     * @param eventType simple class name (vd: "ResourceReservedEvent")
     * @return Class tương ứng, hoặc {@code null} nếu không tìm thấy
     */
    public Class<? extends DomainEvent> lookup(String eventType) {
        return registry.get(eventType);
    }

    public Map<String, Class<? extends DomainEvent>> asMap() {
        return Collections.unmodifiableMap(registry);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Class<? extends DomainEvent>> scan(String[] basePackages) {
        Map<String, Class<? extends DomainEvent>> result = new HashMap<>();

        // useDefaultFilters=false: chỉ nhận class match filter bên dưới
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(
                            org.springframework.beans.factory.annotation.AnnotatedBeanDefinition beanDefinition) {
                        // Nhận cả class không có @Component (events là POJO)
                        return beanDefinition.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new AssignableTypeFilter(DomainEvent.class));

        for (String basePackage : basePackages) {
            Set<org.springframework.beans.factory.config.BeanDefinition> defs =
                    scanner.findCandidateComponents(basePackage);
            for (org.springframework.beans.factory.config.BeanDefinition def : defs) {
                String className = def.getBeanClassName();
                if (className == null) continue;
                try {
                    Class<?> clazz = Class.forName(className);
                    if (Modifier.isAbstract(clazz.getModifiers())) continue;
                    if (!DomainEvent.class.isAssignableFrom(clazz)) continue;
                    Class<? extends DomainEvent> eventClass =
                            (Class<? extends DomainEvent>) clazz;
                    result.put(eventClass.getSimpleName(), eventClass);
                } catch (ClassNotFoundException e) {
                    log.warn("[EventTypeRegistry] Skip unloadable class: {}", className);
                }
            }
        }
        return result;
    }
}
