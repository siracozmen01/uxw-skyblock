package com.uxplima.uxmskyblock.core.application.inactivity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.event.EventId;
import com.uxplima.uxmskyblock.core.domain.event.StagedOutboxEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.AbandonmentAction;
import com.uxplima.uxmskyblock.core.domain.inactivity.InactivityPolicy;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandSuccessionRecord;
import com.uxplima.uxmskyblock.core.domain.inactivity.SuccessionOutcome;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import org.jspecify.annotations.Nullable;

/**
 * Enterprise domain application service governing leader inactivity evaluation,
 * automated dynamic ownership succession hierarchy, deposed leader disposition,
 * and total team abandonment pruning / archival lifecycle.
 */
public final class IslandInactivityService {

    private final IslandStoragePort islandStoragePort;
    private final PlayerActivityProvider playerActivityProvider;
    private final InactivityPolicy policy;
    private final @Nullable IslandArchivalPort archivalPort;
    private final @Nullable IslandRecyclePort recyclePort;
    private final @Nullable OutboxPort outboxPort;

    public IslandInactivityService(
            IslandStoragePort islandStoragePort,
            PlayerActivityProvider playerActivityProvider,
            InactivityPolicy policy,
            @Nullable IslandArchivalPort archivalPort,
            @Nullable IslandRecyclePort recyclePort,
            @Nullable OutboxPort outboxPort) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.playerActivityProvider =
                Objects.requireNonNull(playerActivityProvider, "playerActivityProvider must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.archivalPort = archivalPort;
        this.recyclePort = recyclePort;
        this.outboxPort = outboxPort;
    }

    public IslandInactivityService(
            IslandStoragePort islandStoragePort,
            PlayerActivityProvider playerActivityProvider,
            InactivityPolicy policy) {
        this(islandStoragePort, playerActivityProvider, policy, null, null, null);
    }

    public InactivityPolicy policy() {
        return policy;
    }

    public SuccessionOutcome evaluateIsland(IslandId islandId, Instant now) {
        Objects.requireNonNull(islandId, "islandId must not be null");
        Objects.requireNonNull(now, "now must not be null");

        Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
        if (optIsland.isEmpty()) {
            return SuccessionOutcome.SKIPPED_NO_ELIGIBLE_SUCCESSOR;
        }

        Island island = optIsland.get();
        IslandLocation location = islandStoragePort
                .findLocationByIslandId(islandId)
                .orElseGet(() -> new IslandLocation(islandId, "world", island.bounds(), 0, 100, 0, 0, 0));

        IslandSuccessionRecord record = evaluateIslandInternal(island, location, now);
        return record.outcome();
    }

    public IslandInactivityScanReport evaluateAll(Collection<Island> islands, Instant now) {
        Objects.requireNonNull(islands, "islands must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (!policy.enabled()) {
            return IslandInactivityScanReport.empty(now);
        }

        int total = islands.size();
        int successions = 0;
        int archived = 0;
        int deleted = 0;
        int skipped = 0;
        List<IslandSuccessionRecord> records = new ArrayList<>();

        for (Island island : islands) {
            IslandLocation location = islandStoragePort
                    .findLocationByIslandId(island.id())
                    .orElseGet(() -> new IslandLocation(island.id(), "world", island.bounds(), 0, 100, 0, 0, 0));

            IslandSuccessionRecord record = evaluateIslandInternal(island, location, now);
            records.add(record);

            switch (record.outcome()) {
                case SUCCESSION_EXECUTED -> successions++;
                case ABANDONED_ARCHIVED -> archived++;
                case ABANDONED_DELETED -> deleted++;
                default -> skipped++;
            }
        }

        return new IslandInactivityScanReport(now, total, successions, archived, deleted, skipped, records);
    }

    public IslandInactivityScanReport scanWorld(String worldName, Instant now) {
        Objects.requireNonNull(worldName, "worldName must not be null");
        Objects.requireNonNull(now, "now must not be null");

        List<Island> islands = islandStoragePort.findAllByWorld(worldName);
        return evaluateAll(islands, now);
    }

    private IslandSuccessionRecord evaluateIslandInternal(Island island, IslandLocation location, Instant now) {
        if (!policy.enabled()) {
            return new IslandSuccessionRecord(
                    island.id(),
                    SuccessionOutcome.SKIPPED_POLICY_DISABLED,
                    island.ownerProfileId(),
                    null,
                    now,
                    "Inactivity policy is globally disabled.");
        }

        // 1. Check total team abandonment
        boolean allMembersAbandoned = true;
        for (IslandMember member : island.members().values()) {
            Instant lastActive = playerActivityProvider
                    .getLastActive(member.playerUuid(), member.profileId())
                    .orElse(member.joinedAt());
            if (!isInactivePast(lastActive, policy.allMembersInactivityDuration(), now)) {
                allMembersAbandoned = false;
                break;
            }
        }

        if (allMembersAbandoned) {
            if (policy.abandonmentAction() == AbandonmentAction.ARCHIVE) {
                if (archivalPort != null) {
                    archivalPort.archiveIsland(island.id());
                }
                Island updated = island.withFlags(
                        island.flags().withFlag("ARCHIVED", true).withFlag(IslandFlags.LOCKED, true));
                StagedOutboxEvent outboxEvent = (outboxPort != null)
                        ? new StagedOutboxEvent(
                                EventId.random(),
                                "ISLAND_ARCHIVED",
                                island.id().value().toString(),
                                String.format(
                                        "{\"islandId\":\"%s\",\"reason\":\"Total team inactivity exceeded %s\"}",
                                        island.id().value(), policy.allMembersInactivityDuration()))
                        : null;
                islandStoragePort.saveIsland(updated, location, outboxEvent);
                if (outboxPort != null && outboxEvent != null) {
                    outboxPort.stageEvent(
                            outboxEvent.id(),
                            outboxEvent.eventType(),
                            outboxEvent.aggregateId(),
                            outboxEvent.payload());
                }

                return new IslandSuccessionRecord(
                        island.id(),
                        SuccessionOutcome.ABANDONED_ARCHIVED,
                        island.ownerProfileId(),
                        null,
                        now,
                        "All island members inactive. Island archived and access locked.");
            } else {
                if (recyclePort != null) {
                    recyclePort.recycleIsland(island.id());
                }
                StagedOutboxEvent outboxEvent = (outboxPort != null)
                        ? new StagedOutboxEvent(
                                EventId.random(),
                                "ISLAND_RECYCLED",
                                island.id().value().toString(),
                                String.format(
                                        "{\"islandId\":\"%s\",\"reason\":\"Total team inactivity exceeded %s\"}",
                                        island.id().value(), policy.allMembersInactivityDuration()))
                        : null;
                islandStoragePort.deleteIsland(island.id(), outboxEvent);
                if (outboxPort != null && outboxEvent != null) {
                    outboxPort.stageEvent(
                            outboxEvent.id(),
                            outboxEvent.eventType(),
                            outboxEvent.aggregateId(),
                            outboxEvent.payload());
                }

                return new IslandSuccessionRecord(
                        island.id(),
                        SuccessionOutcome.ABANDONED_DELETED,
                        island.ownerProfileId(),
                        null,
                        now,
                        "All island members inactive. Island deleted and recycled.");
            }
        }

        // 2. Check owner activity
        IslandMember ownerMember = island.member(island.ownerProfileId()).orElse(null);
        Instant ownerLastActive = ownerMember != null
                ? playerActivityProvider
                        .getLastActive(ownerMember.playerUuid(), ownerMember.profileId())
                        .orElse(ownerMember.joinedAt())
                : island.createdAt();

        if (!isInactivePast(ownerLastActive, policy.ownerInactivityDuration(), now)) {
            return new IslandSuccessionRecord(
                    island.id(),
                    SuccessionOutcome.SKIPPED_ACTIVE_OWNER,
                    island.ownerProfileId(),
                    null,
                    now,
                    "Island owner was active recently.");
        }

        // 3. Find eligible successor
        List<IslandRole> hierarchy = policy.successionHierarchy();
        List<IslandMember> eligibleCandidates = new ArrayList<>();

        for (IslandMember member : island.members().values()) {
            if (member.profileId().equals(island.ownerProfileId())) {
                continue;
            }
            if (!hierarchy.contains(member.role())) {
                continue;
            }
            Instant memberLastActive = playerActivityProvider
                    .getLastActive(member.playerUuid(), member.profileId())
                    .orElse(member.joinedAt());
            if (isInactivePast(memberLastActive, policy.ownerInactivityDuration(), now)) {
                continue; // Candidate is also inactive
            }
            eligibleCandidates.add(member);
        }

        if (eligibleCandidates.isEmpty()) {
            return new IslandSuccessionRecord(
                    island.id(),
                    SuccessionOutcome.SKIPPED_NO_ELIGIBLE_SUCCESSOR,
                    island.ownerProfileId(),
                    null,
                    now,
                    "Owner is inactive, but no active eligible successor found in hierarchy.");
        }

        // Sort candidates:
        // 1. Hierarchy rank (earlier index in successionHierarchy = higher priority)
        // 2. Seniority (earliest joinedAt)
        // 3. Deterministic UUID comparison
        eligibleCandidates.sort(Comparator.comparingInt((IslandMember m) -> hierarchy.indexOf(m.role()))
                .thenComparing(IslandMember::joinedAt)
                .thenComparing(m -> m.profileId().value()));

        IslandMember successor = eligibleCandidates.get(0);
        ProfileId formerOwnerId = island.ownerProfileId();

        // 4. Transfer ownership
        Island updatedIsland =
                island.transferOwnership(successor.playerUuid(), successor.profileId(), policy.formerOwnerAction());
        StagedOutboxEvent outboxEvent = (outboxPort != null)
                ? new StagedOutboxEvent(
                        EventId.random(),
                        "ISLAND_LEADER_SUCCESSION",
                        island.id().value().toString(),
                        String.format(
                                "{\"islandId\":\"%s\",\"formerOwnerProfileId\":\"%s\",\"newOwnerProfileId\":\"%s\",\"action\":\"%s\"}",
                                island.id().value(),
                                formerOwnerId.value(),
                                successor.profileId().value(),
                                policy.formerOwnerAction()))
                : null;
        islandStoragePort.saveIsland(updatedIsland, location, outboxEvent);
        if (outboxPort != null && outboxEvent != null) {
            outboxPort.stageEvent(
                    outboxEvent.id(), outboxEvent.eventType(), outboxEvent.aggregateId(), outboxEvent.payload());
        }

        return new IslandSuccessionRecord(
                island.id(),
                SuccessionOutcome.SUCCESSION_EXECUTED,
                formerOwnerId,
                successor.profileId(),
                now,
                String.format(
                        "Transferred ownership from %s to %s with disposition %s.",
                        formerOwnerId, successor.profileId(), policy.formerOwnerAction()));
    }

    private static boolean isInactivePast(Instant lastActive, Duration threshold, Instant now) {
        Duration elapsed = Duration.between(lastActive, now);
        return !elapsed.isNegative() && elapsed.compareTo(threshold) >= 0;
    }
}
