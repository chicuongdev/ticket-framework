package io.hrc.gateway.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet Filter inject correlationId vào SLF4J MDC — mọi log sau đó
 * tự động include correlationId mà không cần developer làm gì thêm.
 *
 * <p><b>Pipeline:</b>
 * <ol>
 *   <li>Lấy {@code X-Correlation-ID} từ HTTP header, hoặc sinh UUID mới nếu không có</li>
 *   <li>Set vào {@link MDC} với key {@value #MDC_KEY}</li>
 *   <li>Set vào HTTP response header {@value #HEADER_NAME} — client trace được</li>
 *   <li>Chain doFilter() — mọi log trong request thread đều có correlationId</li>
 *   <li>Remove khỏi MDC sau response (tránh leak sang request khác trong thread pool)</li>
 * </ol>
 *
 * <p><b>Log tự động:</b>
 * <pre>
 * 2026-04-13 10:00:01 INFO  [correlationId=abc-123] [Saga] Processing: orderId=ord-456...
 * 2026-04-13 10:00:01 INFO  [correlationId=abc-123] [Payment] Charging 500000 VND...
 *
 * → grep "correlationId=abc-123" để trace toàn bộ 1 request
 * </pre>
 *
 * <p><b>Cách đăng ký (Spring Boot):</b>
 * <pre>{@code
 * @Bean
 * public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
 *     FilterRegistrationBean<CorrelationIdFilter> bean = new FilterRegistrationBean<>();
 *     bean.setFilter(new CorrelationIdFilter());
 *     bean.setOrder(Ordered.HIGHEST_PRECEDENCE);  // chạy trước mọi filter khác
 *     bean.addUrlPatterns("/*");
 *     return bean;
 * }
 * }</pre>
 *
 * <p><b>Logback pattern để include correlationId:</b>
 * <pre>
 * &lt;pattern&gt;%d{yyyy-MM-dd HH:mm:ss} %-5level [%X{correlationId}] %logger{36} - %msg%n&lt;/pattern&gt;
 * </pre>
 *
 * <p>Sử dụng {@link OncePerRequestFilter} của Spring để đảm bảo
 * filter chỉ chạy đúng 1 lần per request (tránh duplicate khi có
 * forward/include nội bộ).
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Tên HTTP header mang correlationId — gửi đi và nhận về. */
    public static final String HEADER_NAME = "X-Correlation-ID";

    /** Key trong SLF4J MDC — dùng trong logback pattern. */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);

        // Nếu client không cung cấp → sinh UUID mới cho request này
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        // Inject vào MDC — tất cả log trong thread này sẽ có correlationId
        MDC.put(MDC_KEY, correlationId);

        // Add vào response header — client có thể dùng để report lỗi
        response.setHeader(HEADER_NAME, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // PHẢI remove sau request — thread pool tái sử dụng thread,
            // nếu không remove sẽ leak correlationId của request trước sang request sau
            MDC.remove(MDC_KEY);
        }
    }
}
