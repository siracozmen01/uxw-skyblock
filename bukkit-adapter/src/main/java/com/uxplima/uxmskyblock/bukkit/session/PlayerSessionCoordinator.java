package com.uxplima.uxmskyblock.bukkit.session;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileInventoryCheckpointPort;
import com.uxplima.uxmskyblock.core.application.profile.SwitchProfileUseCase;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.result.Result;
import com.uxplima.uxmskyblock.core.domain.result.Unit;
import com.uxplima.uxmskyblock.core.domain.session.PlayerSessionRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionLease;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.jspecify.annotations.Nullable;

/**
 * Coordinates player session lifecycle, heartbeat renewals, periodic checkpoints,
 * clean handoff flushes, and profile switches on Folia/Paper.
 */
public final class PlayerSessionCoordinator {

    private static final Logger LOGGER = Logger.getLogger(PlayerSessionCoordinator.class.getName());

    private final ServerNodeId nodeId;
    private final PlayerSessionAuthorityPort sessionAuthorityPort;
    private final ProfileInventoryCheckpointPort inventoryCheckpointPort;
    private final ProfileHandoffFinalizationPort handoffFinalizationPort;
    private final SwitchProfileUseCase switchProfileUseCase;
    private final CutShortOperations cutShort;
    private final SchedulerPort schedulerPort;
    private final IslandProtectionListener protectionListener;
    private final Duration heartbeatInterval;
    private final Duration checkpointInterval;

    private final ConcurrentMap<UUID, ActiveSession> activeSessions = new ConcurrentHashMap<>();
    private final Messages messages;
    private final java.util.function.LongSupplier nanoClock;

    public PlayerSessionCoordinator(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileInventoryCheckpointPort inventoryCheckpointPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SwitchProfileUseCase switchProfileUseCase,
            com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery journalRecovery,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            Duration heartbeatInterval,
            Duration checkpointInterval,
            Messages messages) {
        this(
                nodeId,
                sessionAuthorityPort,
                inventoryCheckpointPort,
                handoffFinalizationPort,
                switchProfileUseCase,
                journalRecovery,
                schedulerPort,
                protectionListener,
                heartbeatInterval,
                checkpointInterval,
                messages,
                System::nanoTime);
    }

    /**
     * The same, counting the lease on {@code nanoClock}, a monotonic clock in nanoseconds: a test
     * moves it, a server uses {@link System#nanoTime}.
     */
    public PlayerSessionCoordinator(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileInventoryCheckpointPort inventoryCheckpointPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SwitchProfileUseCase switchProfileUseCase,
            com.uxplima.uxmskyblock.core.application.inventory.InventoryJournalRecovery journalRecovery,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            Duration heartbeatInterval,
            Duration checkpointInterval,
            Messages messages,
            java.util.function.LongSupplier nanoClock) {
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sessionAuthorityPort = Objects.requireNonNull(sessionAuthorityPort, "sessionAuthorityPort");
        this.inventoryCheckpointPort = Objects.requireNonNull(inventoryCheckpointPort, "inventoryCheckpointPort");
        this.handoffFinalizationPort = Objects.requireNonNull(handoffFinalizationPort, "handoffFinalizationPort");
        this.switchProfileUseCase = Objects.requireNonNull(switchProfileUseCase, "switchProfileUseCase");
        this.cutShort = new CutShortOperations(journalRecovery, nodeId);
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.checkpointInterval = Objects.requireNonNull(checkpointInterval, "checkpointInterval");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public @Nullable ActiveSession getActiveSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
    }

    /** Whether {@code playerUuid} holds their session's state here and may use it. */
    public boolean inPlay(UUID playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid);
        return session != null && session.inPlay(nanoClock.getAsLong());
    }

    /**
     * Resolves the canonical active profile for a connected player, if one is currently active and not fenced.
     */
    public Optional<ProfileId> activeProfile(PlayerUuid playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid.value());
        if (session != null && !session.isFenced()) {
            return Optional.of(session.activeProfileId());
        }
        return Optional.empty();
    }

    /**
     * Resolves the canonical active profile for a connected player by Bukkit UUID.
     */
    public Optional<ProfileId> activeProfile(UUID rawUuid) {
        return activeProfile(new PlayerUuid(rawUuid));
    }

    public Optional<ProfileId> findDurableActiveProfile(UUID playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid);
        if (session != null && !session.isFenced()) {
            return Optional.of(session.activeProfileId());
        }
        return sessionAuthorityPort.findSession(new PlayerUuid(playerUuid)).map(PlayerSessionRecord::activeProfileId);
    }

    /**
     * Executes runtime self-fencing when lease renewal fails or is rejected:
     * transitions session to LOCAL_FENCED, cancels timers, and disconnects player fail-closed.
     */
    public void selfFencePlayer(PlayerUuid playerUuid, String reason) {
        ActiveSession session = activeSessions.remove(playerUuid.value());
        if (session != null) {
            session.fence();
            protectionListener.removeActiveProfile(playerUuid);
        }
        LOGGER.log(Level.SEVERE, "Runtime self-fencing player {0}: {1}", new Object[] {playerUuid, reason});
        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player != null && player.isOnline()) {
                player.kick(messages.render(player, "session.lease_lost"));
            }
        });
    }

    /** What runs on the player's thread once their session is made and their inventory applied. */
    private final java.util.List<java.util.function.Consumer<Player>> whenActive =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Runs {@code hook} for each player whose session is made, on the player's own thread.
     *
     * <p>A session is made off the join thread, after the join event, so anything that needs the
     * player's profile cannot ask for it at join.
     */
    public void whenSessionActive(java.util.function.Consumer<Player> hook) {
        whenActive.add(java.util.Objects.requireNonNull(hook, "hook"));
    }

    /** What runs on the player's thread when they switch away from a profile, with that profile. */
    private final java.util.List<java.util.function.BiConsumer<Player, ProfileId>> whenLeft =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Runs {@code hook} when a player switches away from a profile, before the session hooks run for
     * the one they switched to. A switch is a leave and an arrival: anything that tracks who is
     * playing by profile has to hear both, or it keeps the old profile and misses the new one.
     */
    public void whenProfileLeft(java.util.function.BiConsumer<Player, ProfileId> hook) {
        whenLeft.add(java.util.Objects.requireNonNull(hook, "hook"));
    }

    private void runActiveHooks(Player player) {
        for (java.util.function.Consumer<Player> hook : whenActive) {
            try {
                hook.accept(player);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A hook on a new session failed for " + player.getName(), e);
            }
        }
    }

    private void runLeftHooks(Player player, ProfileId left) {
        for (java.util.function.BiConsumer<Player, ProfileId> hook : whenLeft) {
            try {
                hook.accept(player, left);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A hook on a profile switch failed for " + player.getName(), e);
            }
        }
    }

    /**
     * Handles player join: ensures session authority, applies saved inventory, starts heartbeats & checkpoints.
     */
    public void handlePlayerJoin(Player player) {
        Objects.requireNonNull(player, "player");
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        ProfileId defaultProfileId = new ProfileId(rawUuid);

        schedulerPort.async(() -> {
            try {
                long asked = nanoClock.getAsLong();
                SessionAuthorityOutcome outcome =
                        sessionAuthorityPort.ensureSession(playerUuid, defaultProfileId, nodeId);

                if (!outcome.isSuccess()) {
                    schedulerPort.onEntity(playerUuid, () -> {
                        if (player.isOnline()) {
                            player.kick(messages.render(player, "session.authority_refused"));
                        }
                    });
                    return;
                }

                SessionAuthorityOutcome.Success success = (SessionAuthorityOutcome.Success) outcome;
                long epoch = success.epoch();

                // If node took over an expired lease or crash occurred, reconcile in-flight profile switch operations
                switchProfileUseCase.recoverInFlightSwitch(playerUuid, nodeId, epoch);

                if (success.isRecovering()) {
                    sessionAuthorityPort.markRecoveredActive(playerUuid, nodeId, epoch);
                }

                Optional<PlayerSessionRecord> sessionOpt = sessionAuthorityPort.findSession(playerUuid);
                ProfileId activeProfile =
                        sessionOpt.map(PlayerSessionRecord::activeProfileId).orElse(defaultProfileId);

                cutShort.settle(playerUuid, activeProfile, epoch);

                Optional<ProfileInventoryRecord> invOpt = inventoryCheckpointPort.loadInventory(activeProfile);
                long initialVersion =
                        invOpt.map(ProfileInventoryRecord::version).orElse(1L);

                ActiveSession session =
                        new ActiveSession(playerUuid, activeProfile, epoch, initialVersion, SessionState.ACTIVE);
                session.holdUntil(asked + SessionLease.locallyHeld().toNanos());
                activeSessions.put(rawUuid, session);

                // Apply inventory and bind protection profile on entity thread
                schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline() || session.isFenced()) {
                        return;
                    }
                    if (invOpt.isPresent()) {
                        BukkitInventorySerializer.applyToPlayer(player, invOpt.get());
                    }
                    session.enterPlay();
                    protectionListener.setActiveProfile(playerUuid, activeProfile);
                    runActiveHooks(player);
                });

                // Start async heartbeat task with fail-closed self-fencing
                AutoCloseable hbTask = schedulerPort.repeatAsync(
                        () -> {
                            if (session.isFenced()) {
                                return;
                            }
                            // The deadline counts from the moment the renewal was asked for, not from
                            // its answer: the database extended the lease no earlier than that.
                            long renewAsked = nanoClock.getAsLong();
                            SessionAuthorityOutcome renewOutcome =
                                    sessionAuthorityPort.renew(playerUuid, nodeId, session.sessionEpoch());
                            long deadline =
                                    renewAsked + SessionLease.locallyHeld().toNanos();
                            if (!renewOutcome.isSuccess()) {
                                selfFencePlayer(playerUuid, "Lease renewal failed or rejected: " + renewOutcome);
                            } else if (nanoClock.getAsLong() - deadline >= 0) {
                                // An answer later than the deadline it would set: this node stopped
                                // acting on the lease while it waited, and the player is safer moved on.
                                selfFencePlayer(playerUuid, "Lease renewal answered after the local deadline");
                            } else {
                                session.holdUntil(deadline);
                            }
                        },
                        heartbeatInterval,
                        heartbeatInterval);

                // Start async periodic checkpoint task
                AutoCloseable cpTask = schedulerPort.repeatAsync(
                        () -> checkpointPlayer(playerUuid), checkpointInterval, checkpointInterval);

                session.attachTasks(hbTask, cpTask);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unexpected error during player join for " + playerUuid, e);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.kick(messages.render(player, "session.init_error"));
                    }
                });
            }
        });
    }

    /**
     * Ambient checkpoint of player inventory.
     */
    public void checkpointPlayer(PlayerUuid playerUuid) {
        ActiveSession session = activeSessions.get(playerUuid.value());
        if (session == null || session.isFenced()) {
            return;
        }

        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player == null || !player.isOnline() || session.isFenced()) {
                return;
            }

            ProfileInventoryRecord snapshot = BukkitInventorySerializer.snapshotPlayer(
                    player, session.activeProfileId(), session.lastDurableVersion());

            schedulerPort.async(() -> {
                if (session.isFenced()) {
                    return;
                }
                ProfileInventoryMutationOutcome outcome = inventoryCheckpointPort.checkpointInventory(
                        playerUuid,
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        snapshot);

                if (outcome instanceof ProfileInventoryMutationOutcome.Success succ) {
                    session.setLastDurableVersion(succ.newVersion());
                } else {
                    LOGGER.log(
                            Level.WARNING, "Ambient checkpoint rejected for {0}: {1}", new Object[] {playerUuid, outcome
                            });
                }
            });
        });
    }

    /**
     * Handles player quit: cancels background tasks, drains authority, and flushes final inventory.
     */
    public void handlePlayerQuit(Player player) {
        Objects.requireNonNull(player, "player");
        UUID rawUuid = player.getUniqueId();
        ActiveSession session = activeSessions.remove(rawUuid);
        if (session == null || session.isFenced()) {
            return;
        }

        session.closeTasks();
        protectionListener.removeActiveProfile(session.playerUuid());

        // Snapshot current inventory on entity thread before player disconnects
        ProfileInventoryRecord snapshot = BukkitInventorySerializer.snapshotPlayer(
                player, session.activeProfileId(), session.lastDurableVersion());

        schedulerPort.async(() -> {
            try {
                // Drain session first
                SessionAuthorityOutcome drainOutcome =
                        sessionAuthorityPort.drain(session.playerUuid(), nodeId, session.sessionEpoch());
                if (!(drainOutcome instanceof SessionAuthorityOutcome.Success)) {
                    LOGGER.log(Level.SEVERE, "Failed to drain session on quit for {0}: {1}", new Object[] {
                        session.playerUuid(), drainOutcome
                    });
                    return;
                }

                // Authoritatively flush final snapshot
                ProfileInventoryMutationOutcome outcome = handoffFinalizationPort.finalizeHandoffFlush(
                        session.playerUuid(),
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        snapshot);

                if (outcome instanceof ProfileInventoryMutationOutcome.Success succ) {
                    session.setLastDurableVersion(succ.newVersion());
                    // Release lease to OFFLINE state (SES-003)
                    sessionAuthorityPort.releaseToOffline(session.playerUuid(), nodeId, session.sessionEpoch());
                } else {
                    LOGGER.log(Level.SEVERE, "Handoff finalization flush failed on quit for {0}: {1}", new Object[] {
                        session.playerUuid(), outcome
                    });
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error finalizing handoff flush on quit for " + session.playerUuid(), e);
            }
        });
    }

    /**
     * Executes the crash-consistent 2-phase profile switch protocol.
     */
    public void switchProfile(Player player, ProfileId targetProfileId) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetProfileId, "targetProfileId");

        UUID rawUuid = player.getUniqueId();
        ActiveSession session = activeSessions.get(rawUuid);
        if (session == null || session.isFenced()) {
            messages.send(player, "session.none_active");
            return;
        }

        if (session.activeProfileId().equals(targetProfileId)) {
            messages.send(player, "session.already_on_profile");
            return;
        }

        PlayerUuid playerUuid = session.playerUuid();
        ProfileId currentProfile = session.activeProfileId();

        // 1. Snapshot source inventory on entity thread
        schedulerPort.onEntity(playerUuid, () -> {
            if (!player.isOnline() || session.isFenced()) {
                return;
            }

            // Nothing is done with what the player holds until the other profile's state replaces it.
            session.leavePlay();
            ProfileInventoryRecord srcSnapshot =
                    BukkitInventorySerializer.snapshotPlayer(player, currentProfile, session.lastDurableVersion());

            // 2. Prepare switch asynchronously
            schedulerPort.async(() -> {
                if (session.isFenced()) {
                    return;
                }
                UUID opId = UUID.randomUUID();
                Result<SwitchProfileUseCase.PreparedSwitch, String> prepRes = switchProfileUseCase.prepareSwitch(
                        opId, playerUuid, currentProfile, targetProfileId, nodeId, session.sessionEpoch(), srcSnapshot);

                if (prepRes.isErr()) {
                    LOGGER.log(Level.WARNING, "Failed to prepare profile switch: {0}", prepRes.errorOrThrow());
                    schedulerPort.onEntity(playerUuid, () -> {
                        session.enterPlay();
                        if (player.isOnline()) {
                            // The reason is a code for the log, and it used to be shown as it was.
                            messages.send(player, "session.switch_failed");
                        }
                    });
                    return;
                }

                SwitchProfileUseCase.PreparedSwitch prepared = prepRes.orElseThrow();

                // 3. Apply target state on entity thread (restores full inventory, enderchest, stats, potion effects,
                // gamemode, flight)
                schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline() || session.isFenced()) {
                        return;
                    }

                    if (prepared.targetRecord() != null) {
                        BukkitInventorySerializer.applyToPlayer(player, prepared.targetRecord());
                    } else if (prepared.targetInventoryNbt().length > 0) {
                        var items = BukkitInventorySerializer.deserializeItemStacks(prepared.targetInventoryNbt());
                        player.getInventory().setContents(items);
                    } else {
                        player.getInventory().clear();
                        player.getEnderChest().clear();
                    }

                    protectionListener.setActiveProfile(playerUuid, targetProfileId);
                    session.enterPlay();

                    // 4. Complete switch asynchronously
                    schedulerPort.async(() -> {
                        if (session.isFenced()) {
                            return;
                        }
                        Result<Unit, String> compRes = switchProfileUseCase.completeSwitch(prepared);
                        if (compRes.isOk()) {
                            session.setActiveProfileId(targetProfileId);
                            cutShort.settle(playerUuid, targetProfileId, session.sessionEpoch());
                            long newVersion = prepared.targetRecord() != null
                                    ? prepared.targetRecord().version()
                                    : 1L;
                            session.setLastDurableVersion(newVersion);
                            schedulerPort.onEntity(playerUuid, () -> {
                                if (player.isOnline()) {
                                    messages.send(
                                            player,
                                            "session.switched",
                                            Placeholder.unparsed(
                                                    "profile",
                                                    targetProfileId.value().toString()));
                                    // The old profile has left and the new one has arrived.
                                    runLeftHooks(player, currentProfile);
                                    runActiveHooks(player);
                                }
                            });
                        } else {
                            LOGGER.log(Level.SEVERE, "Failed to complete profile switch: {0}", compRes.errorOrThrow());
                        }
                    });
                });
            });
        });
    }

    /**
     * What the player of {@code session} holds, read on this thread if this thread owns the player.
     *
     * <p>Shutdown used to hand the read to the player's own thread and take the answer at once. The
     * plugin is already disabled when shutdown runs, so the scheduler dropped the task, the answer was
     * always empty, and every player online at a restart had an empty inventory written over theirs.
     * By now the server has stopped ticking: Paper disables plugins on its main thread and Folia on its
     * shutdown thread, and each of those owns every player. A thread that does not own the player
     * reads nothing, and nothing is written.
     */
    private Optional<ProfileInventoryRecord> heldState(ActiveSession session) {
        Player player = Bukkit.getPlayer(session.playerUuid().value());
        if (player == null || !player.isOnline() || !schedulerPort.ownsEntity(session.playerUuid())) {
            return Optional.empty();
        }
        return Optional.of(BukkitInventorySerializer.snapshotPlayer(
                player, session.activeProfileId(), session.lastDurableVersion()));
    }

    /**
     * Gracefully shuts down all active player sessions during plugin disable.
     */
    public void shutdown() {
        for (ActiveSession session : activeSessions.values()) {
            if (session.isFenced()) {
                continue;
            }
            session.closeTasks();
            try {
                Optional<ProfileInventoryRecord> held = heldState(session);

                SessionAuthorityOutcome drainOutcome =
                        sessionAuthorityPort.drain(session.playerUuid(), nodeId, session.sessionEpoch());
                if (!drainOutcome.isSuccess()) {
                    LOGGER.log(Level.SEVERE, "Failed to drain session during shutdown for {0}: {1}", new Object[] {
                        session.playerUuid(), drainOutcome
                    });
                    continue;
                }

                if (held.isEmpty()) {
                    // Nothing was read, so nothing is written over what the last checkpoint kept.
                    LOGGER.log(
                            Level.WARNING,
                            "The inventory of {0} could not be read at shutdown; the last checkpoint stands.",
                            session.playerUuid());
                    sessionAuthorityPort.releaseToOffline(session.playerUuid(), nodeId, session.sessionEpoch());
                    continue;
                }

                ProfileInventoryMutationOutcome outcome = handoffFinalizationPort.finalizeHandoffFlush(
                        session.playerUuid(),
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        held.get());

                if (outcome instanceof ProfileInventoryMutationOutcome.Success succ) {
                    session.setLastDurableVersion(succ.newVersion());
                    sessionAuthorityPort.releaseToOffline(session.playerUuid(), nodeId, session.sessionEpoch());
                } else {
                    LOGGER.log(
                            Level.SEVERE,
                            "Handoff finalization flush failed during shutdown for {0}: {1}",
                            new Object[] {session.playerUuid(), outcome});
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error draining session during shutdown for " + session.playerUuid(), e);
            }
        }
        activeSessions.clear();
    }
}
