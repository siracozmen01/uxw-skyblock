package com.uxplima.uxmskyblock.core.domain.access;

import java.util.Objects;
import java.util.UUID;

/**
 * Canonical node-local runtime identity used for validating {@link TerminationPolicy#NODE_PROCESS_RESTART}.
 *
 * @param nodeId unique node identifier
 * @param processGenerationId boot generation token unique per JVM startup
 */
public record CurrentNodeProcessIdentity(String nodeId, String processGenerationId) {

    public CurrentNodeProcessIdentity {
        Objects.requireNonNull(nodeId, "nodeId must not be null");
        Objects.requireNonNull(processGenerationId, "processGenerationId must not be null");
    }

    public static CurrentNodeProcessIdentity create(String nodeId) {
        return new CurrentNodeProcessIdentity(nodeId, UUID.randomUUID().toString());
    }
}
