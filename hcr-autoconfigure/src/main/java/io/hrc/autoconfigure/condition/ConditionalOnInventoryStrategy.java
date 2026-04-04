package io.hrc.autoconfigure.condition;

import java.lang.annotation.*;

/** Conditional bean creation based on hcr.inventory.strategy value. */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConditionalOnInventoryStrategy {
    String value();
}
