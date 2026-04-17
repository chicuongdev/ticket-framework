package io.hrc.autoconfigure.condition;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/**
 * Spring Condition backing {@link ConditionalOnInventoryStrategy}.
 * Đọc property {@code hcr.inventory.strategy} và so sánh với annotation value.
 */
public class OnInventoryStrategyCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(
                ConditionalOnInventoryStrategy.class.getName());
        if (attrs == null) return false;

        String required = (String) attrs.get("value");
        String actual = context.getEnvironment()
                .getProperty("hcr.inventory.strategy", "pessimistic-lock");

        return required.equals(actual);
    }
}
