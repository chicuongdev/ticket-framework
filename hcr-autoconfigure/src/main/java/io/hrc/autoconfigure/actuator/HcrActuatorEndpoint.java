package io.hrc.autoconfigure.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * Exposes /actuator/hcr for debugging active configuration.
 * Shows: active strategy, event bus type, saga mode, rate limiter status.
 */
@Endpoint(id = "hcr")
public class HcrActuatorEndpoint {
    @ReadOperation
    public Object info() { return null; /* TODO */ }
}
