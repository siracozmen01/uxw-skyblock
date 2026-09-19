package com.uxplima.uxmskyblock.core.application.network;

import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Sealed outcome of network routing resolution for an island visit or action.
 */
public sealed interface RouteOutcome {

    record Local(IslandId islandId) implements RouteOutcome {
        public Local {
            Objects.requireNonNull(islandId, "islandId must not be null");
        }
    }

    record CrossServer(ServerNodeId targetNode, IslandId islandId) implements RouteOutcome {
        public CrossServer {
            Objects.requireNonNull(targetNode, "targetNode must not be null");
            Objects.requireNonNull(islandId, "islandId must not be null");
        }
    }

    record Unavailable(String reasonCode) implements RouteOutcome {
        public Unavailable {
            Objects.requireNonNull(reasonCode, "reasonCode must not be null");
        }
    }
}
