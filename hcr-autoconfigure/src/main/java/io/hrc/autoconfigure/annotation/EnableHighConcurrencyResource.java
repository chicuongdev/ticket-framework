package io.hrc.autoconfigure.annotation;

import io.hrc.autoconfigure.HcrAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * Đặt trên {@code @SpringBootApplication} để kích hoạt toàn bộ HCR framework.
 *
 * <pre>{@code
 * @SpringBootApplication
 * @EnableHighConcurrencyResource
 * public class ConcertTicketApplication {
 *     public static void main(String[] args) {
 *         SpringApplication.run(ConcertTicketApplication.class, args);
 *     }
 * }
 * }</pre>
 *
 * <p>Import {@link HcrAutoConfiguration} và kích hoạt tất cả beans theo YAML config.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(HcrAutoConfiguration.class)
public @interface EnableHighConcurrencyResource {}
