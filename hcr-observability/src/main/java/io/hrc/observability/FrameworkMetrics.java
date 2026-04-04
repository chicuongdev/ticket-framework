package io.hrc.observability;

/**
 * Metrics contract. Auto-tracked across all modules via Micrometer -> Prometheus.
 * Total 35 metrics: 10 inventory + 5 saga + 6 payment + 6 reconciliation + 4 event bus + 4 gateway.
 */
public interface FrameworkMetrics {}
