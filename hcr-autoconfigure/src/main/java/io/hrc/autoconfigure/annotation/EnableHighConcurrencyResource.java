package io.hrc.autoconfigure.annotation;

import java.lang.annotation.*;

/**
 * Place on @SpringBootApplication to activate the full HCR framework.
 * Imports HcrAutoConfiguration. Single annotation replaces all manual bean wiring.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface EnableHighConcurrencyResource {}
