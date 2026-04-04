package io.hrc.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * YAML-to-Java mapping, validated at startup.
 * Root prefix: hcr.*
 * Sections: inventory, saga, payment, event-bus, gateway, reconciliation.
 */
@ConfigurationProperties(prefix = "hcr")
public class HcrProperties {}
