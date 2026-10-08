package com.uxplima.uxmskyblock.core.application.tradewinds;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/** Where each vessel is bound, written under the vessel's lease. */
public interface VoyagesPort {

    /** A vessel bound for {@code portId}, there from {@code arrivesAt} on. */
    record Voyage(String portId, Instant arrivesAt) {
        public Voyage {
            Objects.requireNonNull(portId, "portId");
            Objects.requireNonNull(arrivesAt, "arrivesAt");
        }
    }

    /** Where {@code vessel} is bound, or empty when it never sailed. */
    Optional<Voyage> voyage(IslandId vessel);

    /**
     * Sets {@code vessel} on its way to {@code voyage}'s port, while {@code node} holds its lease at
     * {@code leaseEpoch}; says whether it did.
     */
    boolean setSail(IslandId vessel, long leaseEpoch, ServerNodeId node, Voyage voyage);
}
