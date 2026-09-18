package com.uxplima.uxmskyblock.core.domain.inactivity;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.core.domain.island.IslandRole;

/**
 * Immutable policy configuration governing leader inactivity evaluation, succession hierarchy,
 * deposed leader disposition, and total team abandonment handling.
 */
public record InactivityPolicy(
        boolean enabled,
        Duration ownerInactivityDuration,
        Duration allMembersInactivityDuration,
        List<IslandRole> successionHierarchy,
        FormerOwnerAction formerOwnerAction,
        AbandonmentAction abandonmentAction) {

    public InactivityPolicy {
        Objects.requireNonNull(ownerInactivityDuration, "ownerInactivityDuration must not be null");
        Objects.requireNonNull(allMembersInactivityDuration, "allMembersInactivityDuration must not be null");
        Objects.requireNonNull(successionHierarchy, "successionHierarchy must not be null");
        Objects.requireNonNull(formerOwnerAction, "formerOwnerAction must not be null");
        Objects.requireNonNull(abandonmentAction, "abandonmentAction must not be null");

        if (ownerInactivityDuration.isNegative() || ownerInactivityDuration.isZero()) {
            throw new IllegalArgumentException("ownerInactivityDuration must be strictly positive");
        }
        if (allMembersInactivityDuration.isNegative() || allMembersInactivityDuration.isZero()) {
            throw new IllegalArgumentException("allMembersInactivityDuration must be strictly positive");
        }
        if (allMembersInactivityDuration.compareTo(ownerInactivityDuration) < 0) {
            throw new IllegalArgumentException(
                    "allMembersInactivityDuration cannot be shorter than ownerInactivityDuration");
        }

        successionHierarchy = Collections.unmodifiableList(List.copyOf(successionHierarchy));
    }

    public static InactivityPolicy defaultPolicy() {
        return new InactivityPolicy(
                true,
                Duration.ofDays(30),
                Duration.ofDays(60),
                List.of(IslandRole.CO_OWNER, IslandRole.MODERATOR, IslandRole.MEMBER),
                FormerOwnerAction.DEMOTE_TO_CO_OWNER,
                AbandonmentAction.ARCHIVE);
    }
}
