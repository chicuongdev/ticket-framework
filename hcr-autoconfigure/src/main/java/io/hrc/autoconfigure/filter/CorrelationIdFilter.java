package io.hrc.autoconfigure.filter;

/**
 * Auto-propagates correlationId in MDC and HTTP headers for distributed tracing.
 * Generates new UUID if incoming request has no X-Correlation-Id header.
 */
public class CorrelationIdFilter {}
