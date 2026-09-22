package com.uxplima.uxmskyblock.core.application.alliance;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import com.uxplima.uxmskyblock.core.application.announce.IslandAnnouncer;
import com.uxplima.uxmskyblock.core.application.island.IslandMutationLock;
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
import org.jspecify.annotations.Nullable;

/**
 * Domain application service coordinating bilateral island alliances, handshake invitations,
 * limits, diplomatic privileges, and friendly-fire shielding.
 */
public final class IslandAllianceService {

    public static final int DEFAULT_MAX_ALLIES = 2;
    public static final Duration DEFAULT_INVITE_TIMEOUT = Duration.ofMinutes(5);

    /**
     * How long an answer about two islands being allied stays good enough for a combat check.
     *
     * <p>Friendly fire shielding asks this on every hit, and a hit is not rare. Every alliance this
     * node forms or breaks clears the answer at once, so this only bounds how long it takes to
     * notice one formed on another node: half a minute of shielding that should have lifted, or
     * should have applied, at the moment two allies stop being allies mid fight.
     */
    public static final Duration ALLIANCE_CACHE_TTL = Duration.ofSeconds(30);

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandAllianceService.class.getName());

    private @Nullable IslandAnnouncer announcer;

    private final IslandAllianceStoragePort storagePort;
    private final int maxAllies;
    private final Duration inviteTimeout;
    private final boolean friendlyFireShielding;
    private final boolean privilegedVisitAccess;
    private final boolean allianceChatEnabled;

    /**
     * Whether two islands are allied, as last read, keyed by the pair in a fixed order.
     *
     * <p>{@code areAllied} is called from the damage handler, so it ran a query per hit on the
     * event thread. An alliance is formed once and asked about constantly.
     */
    private final Map<AlliancePair, CachedAnswer> allianceAnswers = new ConcurrentHashMap<>();

    /**
     * Makes counting the allies and writing the next alliance one thing.
     *
     * <p>Both islands are counted against the cap and then an alliance is written. Two acceptances
     * at once both read one below the cap, both pass, and both write: an island ends up with more
     * allies than the server allows.
     */
    private final IslandMutationLock mutationLock = new IslandMutationLock();

    /** Two islands in a fixed order, so A against B and B against A are one question. */
    private record AlliancePair(String first, String second) {
        static AlliancePair of(IslandId a, IslandId b) {
            String left = a.value().toString();
            String right = b.value().toString();
            return left.compareTo(right) <= 0 ? new AlliancePair(left, right) : new AlliancePair(right, left);
        }
    }

    private record CachedAnswer(boolean allied, Instant readAt) {}

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

        // Both islands are counted, so both are held, and always in the same order so two
        // acceptances that name the same pair the other way round cannot wait on each other.
        AlliancePair pair = AlliancePair.of(senderIsland, targetIsland);
        IslandId first = IslandId.fromString(pair.first());
        IslandId second = IslandId.fromString(pair.second());
        IslandAlliance alliance = mutationLock.inside(
                first, () -> mutationLock.inside(second, () -> writeAlliance(senderIsland, targetIsland, now)));
        announce("ALLIANCE_FORMED", senderIsland, targetIsland);
        return alliance;
    }

    /** Counts both islands against the cap and writes the alliance. Runs inside both locks. */
    private IslandAlliance writeAlliance(IslandId senderIsland, IslandId targetIsland, Instant now) {
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
        forgetAnswersFor(senderIsland, targetIsland);
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
        forgetAnswersFor(islandA, islandB);
        announce("ALLIANCE_DISSOLVED", islandA, islandB);
    }

    /**
     * Tells whoever is listening that two islands changed their standing.
     *
     * <p>Only after the write, and never in a way that can undo it: an announcement that throws is
     * logged and the alliance stands. A node with nowhere to announce to does nothing here.
     */
    private void announce(String action, IslandId one, IslandId other) {
        IslandAnnouncer listener = this.announcer;
        if (listener == null) {
            return;
        }
        try {
            AlliancePair pair = AlliancePair.of(one, other);
            listener.notifyAlliance(
                    pair.first() + " & " + pair.second(),
                    action,
                    one.value().toString(),
                    other.value().toString());
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, e, () -> "An alliance announcement failed. The alliance itself stands.");
        }
    }

    /** Where to announce a change of standing, or nothing when this node announces nowhere. */
    public void setAnnouncer(@Nullable IslandAnnouncer announcer) {
        this.announcer = announcer;
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
        AlliancePair pair = AlliancePair.of(islandA, islandB);
        Instant now = Instant.now();
        CachedAnswer cached = allianceAnswers.get(pair);
        if (cached != null && !cached.readAt().plus(ALLIANCE_CACHE_TTL).isBefore(now)) {
            return cached.allied();
        }
        boolean allied = storagePort.areAllied(islandA, islandB);
        allianceAnswers.put(pair, new CachedAnswer(allied, now));
        return allied;
    }

    /** Forgets what was last read about two islands, because one of them just changed. */
    private void forgetAnswersFor(IslandId islandA, IslandId islandB) {
        allianceAnswers.remove(AlliancePair.of(islandA, islandB));
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
