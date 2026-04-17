package io.hrc.autoconfigure.condition;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.*;

/**
 * Conditional annotation — bean chỉ được tạo khi {@code hcr.inventory.strategy}
 * khớp với giá trị {@link #value()}.
 *
 * <pre>{@code
 * @Bean
 * @ConditionalOnInventoryStrategy("redis-atomic")
 * public RedisAtomicStrategy redisAtomicStrategy(...) { ... }
 * }</pre>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnInventoryStrategyCondition.class)
public @interface ConditionalOnInventoryStrategy {
    /** Giá trị strategy cần match: "pessimistic-lock" | "optimistic-lock" | "redis-atomic". */
    String value();
}
