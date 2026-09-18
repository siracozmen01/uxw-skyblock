package com.uxplima.uxmskyblock.core.application.alliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.alliance.AllianceId;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteExpiredException;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteId;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceInviteNotFoundException;
import com.uxplima.uxmskyblock.core.domain.alliance.AllianceLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.alliance.AlreadyAlliedException;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAlliance;
import com.uxplima.uxmskyblock.core.domain.alliance.IslandAllianceInvite;
import com.uxplima.uxmskyblock.core.domain.alliance.SelfAllianceNotAllowedException;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Domain application service coordinating bilateral island alliances, handshake invitations,
 * limits, diplomatic privileges, and friendly-fire shielding.
 */
public final class IslandAllianceService {

    public static final int DEFAULT_MAX_ALLIES = 2;
    public static final Duration DEFAULT_INVITE_TIMEOUT = Duration.ofMinutes(5);

    private final IslandAllianceStoragePort storagePort;
    private final int maxAllies;
    private final Duration inviteTimeout;
    private final boolean friendlyFireShielding;
    private final boolean privilegedVisitAccess;
    private final boolean allianceChatEnabled;

    public IslandAllianceService(
            IslandAllianceStoragePort storagePort,
            int maxAllies,
            Duration inviteTimeout,
            boolean friendlyFireShielding,
            boolean privilegedVisitAccess,
            boolean allianceChatEnabled) {
        this.storagePort = Objects.requireNonNull(storagePort, "storagePort must not be null");
        this.maxAllies = maxAllies > 0 ? maxAllies : DEFAULT_MAX_ALLIES;
        this.inviteTimeout = Objects.requireNonNull(inviteTimeout, "inviteTimeout must not be null");
        this.friendlyFireShielding = friendlyFireShielding;
        this.privilegedVisitAccess = privilegedVisitAccess;
        this.allianceChatEnabled = allianceChatEnabled;
    }

    public IslandAllianceService(IslandAllianceStoragePort storagePort) {
        this(storagePort, DEFAULT_MAX_ALLIES, DEFAULT_INVITE_TIMEOUT, true, true, true);
    }

    /**
     * Issues a bilateral alliance invitation from senderIsland to targetIsland.
     *
     * @param senderIsland the sending island
     * @param targetIsland the target island
     * @param senderProfile the player profile initiating the invite
     * @return the created pending invite
     */
    public IslandAllianceInvite sendInvite(IslandId senderIsland, IslandId targetIsland, ProfileId senderProfile) {
        Objects.requireNonNull(senderIsland, "senderIsland must not be null");
        Objects.requireNonNull(targetIsland, "targetIsland must not be null");
        Objects.requireNonNull(senderProfile, "senderProfile must not be null");

        if (senderIsland.equals(targetIsland)) {
            throw new SelfAllianceNotAllowedException(senderIsland);
        }

        if (storagePort.areAllied(senderIsland, targetIsland)) {
            throw new AlreadyAlliedException(senderIsland, targetIsland);
        }

        int senderCount = storagePort.countAlliances(senderIsland);
        if (senderCount >= maxAllies) {
            throw new AllianceLimitExceededException(senderIsland, senderCount, maxAllies);
        }

        int targetCount = storagePort.countAlliances(targetIsland);
        if (targetCount >= maxAllies) {
            throw new AllianceLimitExceededException(targetIsland, targetCount, maxAllies);
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plus(inviteTimeout);
        IslandAllianceInvite invite = new IslandAllianceInvite(
                AllianceInviteId.random(), senderIsland, targetIsland, senderProfile, now, expiresAt);

        storagePort.saveInvite(invite);
        return invite;
    }

    /**
     * Accepts a pending alliance invitation from senderIsland to targetIsland.
     *
     * @param senderIsland the island that issued the invite
     * @param targetIsland the island accepting the invite
     * @return the newly established bilateral alliance
     */
    public IslandAlliance acceptInvite(IslandId senderIsland, IslandId targetIsland) {
        Objects.requireNonNull(senderIsland, "senderIsland must not be null");
        Objects.requireNonNull(targetIsland, "targetIsland must not be null");

        Optional<IslandAllianceInvite> maybeInvite = storagePort.findInvite(senderIsland, targetIsland);
        if (maybeInvite.isEmpty()) {
            throw new AllianceInviteNotFoundException(senderIsland, targetIsland);
        }

        IslandAllianceInvite invite = maybeInvite.get();
        Instant now = Instant.now();
        if (invite.isExpired(now)) {
            storagePort.deleteInvite(senderIsland, targetIsland);
            throw new AllianceInviteExpiredException(senderIsland, targetIsland, invite.expiresAt());
        }

        int senderCount = storagePort.countAlliances(senderIsland);
        if (senderCount >= maxAllies) {
            throw new AllianceLimitExceededException(senderIsland, senderCount, maxAllies);
        }

        int targetCount = storagePort.countAlliances(targetIsland);
        if (targetCount >= maxAllies) {
            throw new AllianceLimitExceededException(targetIsland, targetCount, maxAllies);
        }

        IslandAlliance alliance = IslandAlliance.canonical(AllianceId.random(), senderIsland, targetIsland, now);
        storagePort.saveAlliance(alliance);
        storagePort.deleteInvite(senderIsland, targetIsland);
        return alliance;
    }

    /**
     * Declines or dismisses a pending alliance invitation.
     */
    public void declineInvite(IslandId senderIsland, IslandId targetIsland) {
        Objects.requireNonNull(senderIsland, "senderIsland must not be null");
        Objects.requireNonNull(targetIsland, "targetIsland must not be null");
        storagePort.deleteInvite(senderIsland, targetIsland);
    }

    /**
     * Dissolves an established bilateral alliance between two islands.
     */
    public void removeAlliance(IslandId islandA, IslandId islandB) {
        Objects.requireNonNull(islandA, "islandA must not be null");
        Objects.requireNonNull(islandB, "islandB must not be null");
        storagePort.removeAlliance(islandA, islandB);
    }

    /**
     * Checks if two islands are currently allied.
     */
    public boolean areAllied(IslandId islandA, IslandId islandB) {
        Objects.requireNonNull(islandA, "islandA must not be null");
        Objects.requireNonNull(islandB, "islandB must not be null");
        if (islandA.equals(islandB)) {
            return false;
        }
        return storagePort.areAllied(islandA, islandB);
    }

    /**
     * Returns all active allied islands for the specified island.
     */
    public List<IslandId> getAllies(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return storagePort.findAlliances(islandId).stream()
                .map(alliance -> alliance.getPartner(islandId))
                .toList();
    }

    /**
     * Returns the total count of active alliances for the specified island.
     */
    public int getAllianceCount(IslandId islandId) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        return storagePort.countAlliances(islandId);
    }

    /**
     * Retrieves all pending non-expired invites received by targetIsland.
     */
    public List<IslandAllianceInvite> getPendingInvites(IslandId targetIsland) {
        Objects.requireNonNull(targetIsland, "targetIsland must not be null");
        return storagePort.findPendingInvites(targetIsland, Instant.now());
    }

    /**
     * Evaluates if friendly fire PvP damage is shielded between two islands.
     */
    public boolean isFriendlyFireShielded(IslandId attackerIsland, IslandId victimIsland) {
        Objects.requireNonNull(attackerIsland, "attackerIsland must not be null");
        Objects.requireNonNull(victimIsland, "victimIsland must not be null");
        return friendlyFireShielding && areAllied(attackerIsland, victimIsland);
    }

    /**
     * Evaluates if visitor has privileged bypass access to visit a locked island.
     */
    public boolean canPrivilegedVisit(IslandId visitorIsland, IslandId targetIsland) {
        Objects.requireNonNull(visitorIsland, "visitorIsland must not be null");
        Objects.requireNonNull(targetIsland, "targetIsland must not be null");
        return privilegedVisitAccess && areAllied(visitorIsland, targetIsland);
    }

    public int maxAllies() {
        return maxAllies;
    }

    public Duration inviteTimeout() {
        return inviteTimeout;
    }

    public boolean isFriendlyFireShieldingEnabled() {
        return friendlyFireShielding;
    }

    public boolean isPrivilegedVisitAccessEnabled() {
        return privilegedVisitAccess;
    }

    public boolean isAllianceChatEnabled() {
        return allianceChatEnabled;
    }
}
