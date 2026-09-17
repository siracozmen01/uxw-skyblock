package com.uxplima.uxmskyblock.bukkit.session;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

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
import org.jspecify.annotations.Nullable;

/**
 * Coordinates player session lifecycle, heartbeat renewals, periodic checkpoints,
 * clean handoff flushes, and profile switches on Folia/Paper.
 */
public final class PlayerSessionCoordinator {

    private static final Logger LOGGER = Logger.getLogger(PlayerSessionCoordinator.class.getName());

    public static final class ActiveSession {
        private final PlayerUuid playerUuid;
        private volatile ProfileId activeProfileId;
        private final AtomicLong sessionEpoch;
        private final AtomicLong lastDurableVersion;
        private final AtomicReference<@Nullable AutoCloseable> heartbeatTask = new AtomicReference<>(null);
        private final AtomicReference<@Nullable AutoCloseable> checkpointTask = new AtomicReference<>(null);

        public ActiveSession(
                PlayerUuid playerUuid, ProfileId activeProfileId, long sessionEpoch, long lastDurableVersion) {
            this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
            this.activeProfileId = Objects.requireNonNull(activeProfileId, "activeProfileId");
            this.sessionEpoch = new AtomicLong(sessionEpoch);
            this.lastDurableVersion = new AtomicLong(lastDurableVersion);
        }

        public PlayerUuid playerUuid() {
            return playerUuid;
        }

        public ProfileId activeProfileId() {
            return activeProfileId;
        }

        public void setActiveProfileId(ProfileId activeProfileId) {
            this.activeProfileId = Objects.requireNonNull(activeProfileId, "activeProfileId");
        }

        public long sessionEpoch() {
            return sessionEpoch.get();
        }

        public long lastDurableVersion() {
            return lastDurableVersion.get();
        }

        public void setLastDurableVersion(long version) {
            this.lastDurableVersion.set(version);
        }

        @SuppressWarnings("EmptyCatch")
        public void closeTasks() {
            AutoCloseable hb = heartbeatTask.getAndSet(null);
            if (hb != null) {
                try {
                    hb.close();
                } catch (Exception ignored) {
                }
            }
            AutoCloseable cp = checkpointTask.getAndSet(null);
            if (cp != null) {
                try {
                    cp.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private final ServerNodeId nodeId;
    private final PlayerSessionAuthorityPort sessionAuthorityPort;
    private final ProfileInventoryCheckpointPort inventoryCheckpointPort;
    private final ProfileHandoffFinalizationPort handoffFinalizationPort;
    private final SwitchProfileUseCase switchProfileUseCase;
    private final SchedulerPort schedulerPort;
    private final IslandProtectionListener protectionListener;
    private final Duration heartbeatInterval;
    private final Duration checkpointInterval;

    private final ConcurrentMap<UUID, ActiveSession> activeSessions = new ConcurrentHashMap<>();

    public PlayerSessionCoordinator(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileInventoryCheckpointPort inventoryCheckpointPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SwitchProfileUseCase switchProfileUseCase,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            Duration heartbeatInterval,
            Duration checkpointInterval) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sessionAuthorityPort = Objects.requireNonNull(sessionAuthorityPort, "sessionAuthorityPort");
        this.inventoryCheckpointPort = Objects.requireNonNull(inventoryCheckpointPort, "inventoryCheckpointPort");
        this.handoffFinalizationPort = Objects.requireNonNull(handoffFinalizationPort, "handoffFinalizationPort");
        this.switchProfileUseCase = Objects.requireNonNull(switchProfileUseCase, "switchProfileUseCase");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        this.checkpointInterval = Objects.requireNonNull(checkpointInterval, "checkpointInterval");
    }

    public @Nullable ActiveSession getActiveSession(UUID playerUuid) {
        return activeSessions.get(playerUuid);
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
                SessionAuthorityOutcome outcome =
                        sessionAuthorityPort.ensureSession(playerUuid, defaultProfileId, nodeId);

                if (!outcome.isSuccess()) {
                    schedulerPort.onEntity(playerUuid, () -> {
                        if (player.isOnline()) {
                            player.kick(Component.text(
                                    "Failed to acquire player session authority. Another node may still hold your lease. Please reconnect in a few seconds.",
                                    NamedTextColor.RED));
                        }
                    });
                    return;
                }

                long epoch = ((SessionAuthorityOutcome.Success) outcome).epoch();
                Optional<PlayerSessionRecord> sessionOpt = sessionAuthorityPort.findSession(playerUuid);
                ProfileId activeProfile =
                        sessionOpt.map(PlayerSessionRecord::activeProfileId).orElse(defaultProfileId);

                Optional<ProfileInventoryRecord> invOpt = inventoryCheckpointPort.loadInventory(activeProfile);
                long initialVersion =
                        invOpt.map(ProfileInventoryRecord::version).orElse(1L);

                ActiveSession session = new ActiveSession(playerUuid, activeProfile, epoch, initialVersion);
                activeSessions.put(rawUuid, session);

                // Apply inventory and bind protection profile on entity thread
                schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    if (invOpt.isPresent()) {
                        BukkitInventorySerializer.applyToPlayer(player, invOpt.get());
                    }
                    protectionListener.setActiveProfile(playerUuid, activeProfile);
                });

                // Start async heartbeat task
                AutoCloseable hbTask = schedulerPort.repeatAsync(
                        () -> {
                            SessionAuthorityOutcome renewOutcome =
                                    sessionAuthorityPort.renew(playerUuid, nodeId, session.sessionEpoch());
                            if (!renewOutcome.isSuccess()) {
                                LOGGER.log(Level.WARNING, "Failed to renew heartbeat lease for {0}", playerUuid);
                            }
                        },
                        heartbeatInterval,
                        heartbeatInterval);
                session.heartbeatTask.set(hbTask);

                // Start async periodic checkpoint task
                AutoCloseable cpTask = schedulerPort.repeatAsync(
                        () -> checkpointPlayer(playerUuid), checkpointInterval, checkpointInterval);
                session.checkpointTask.set(cpTask);

            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Unexpected error during player join for " + playerUuid, e);
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.kick(Component.text("Internal session initialization error.", NamedTextColor.RED));
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
        if (session == null) {
            return;
        }

        schedulerPort.onEntity(playerUuid, () -> {
            Player player = Bukkit.getPlayer(playerUuid.value());
            if (player == null || !player.isOnline()) {
                return;
            }

            ProfileInventoryRecord snapshot = BukkitInventorySerializer.snapshotPlayer(
                    player, session.activeProfileId(), session.lastDurableVersion());

            schedulerPort.async(() -> {
                ProfileInventoryMutationOutcome outcome = inventoryCheckpointPort.checkpointInventory(
                        playerUuid,
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        snapshot.inventoryNbt());

                if (outcome instanceof ProfileInventoryMutationOutcome.Success success) {
                    session.setLastDurableVersion(success.newVersion());
                } else {
                    LOGGER.log(Level.WARNING, "Ambient checkpoint rejected for {0}", playerUuid);
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
        if (session == null) {
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
                sessionAuthorityPort.drain(session.playerUuid(), nodeId, session.sessionEpoch());
                // Authoritatively flush final snapshot
                ProfileInventoryMutationOutcome outcome = handoffFinalizationPort.finalizeHandoffFlush(
                        session.playerUuid(),
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        snapshot.inventoryNbt());

                if (outcome instanceof ProfileInventoryMutationOutcome.Success success) {
                    session.setLastDurableVersion(success.newVersion());
                } else {
                    LOGGER.log(Level.SEVERE, "Handoff finalization flush failed for {0}", session.playerUuid());
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
        if (session == null) {
            player.sendMessage(Component.text("No active session found.", NamedTextColor.RED));
            return;
        }

        if (session.activeProfileId().equals(targetProfileId)) {
            player.sendMessage(Component.text("You are already on this profile.", NamedTextColor.YELLOW));
            return;
        }

        PlayerUuid playerUuid = session.playerUuid();
        ProfileId currentProfile = session.activeProfileId();

        // 1. Snapshot source inventory on entity thread
        schedulerPort.onEntity(playerUuid, () -> {
            if (!player.isOnline()) {
                return;
            }

            ProfileInventoryRecord srcSnapshot =
                    BukkitInventorySerializer.snapshotPlayer(player, currentProfile, session.lastDurableVersion());

            // 2. Prepare switch asynchronously
            schedulerPort.async(() -> {
                UUID opId = UUID.randomUUID();
                Result<SwitchProfileUseCase.PreparedSwitch, String> prepRes = switchProfileUseCase.prepareSwitch(
                        opId,
                        playerUuid,
                        currentProfile,
                        targetProfileId,
                        nodeId,
                        session.sessionEpoch(),
                        srcSnapshot.inventoryNbt());

                if (prepRes.isErr()) {
                    LOGGER.log(Level.WARNING, "Failed to prepare profile switch: {0}", prepRes.errorOrThrow());
                    schedulerPort.onEntity(playerUuid, () -> {
                        if (player.isOnline()) {
                            player.sendMessage(Component.text(
                                    "Failed to switch profile: " + prepRes.errorOrThrow(), NamedTextColor.RED));
                        }
                    });
                    return;
                }

                SwitchProfileUseCase.PreparedSwitch prepared = prepRes.orElseThrow();

                // 3. Apply target inventory on entity thread
                schedulerPort.onEntity(playerUuid, () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    if (prepared.targetInventoryNbt().length > 0) {
                        var items = BukkitInventorySerializer.deserializeItemStacks(prepared.targetInventoryNbt());
                        player.getInventory().setContents(items);
                    } else {
                        player.getInventory().clear();
                        player.getEnderChest().clear();
                    }

                    protectionListener.setActiveProfile(playerUuid, targetProfileId);

                    // 4. Complete switch asynchronously
                    schedulerPort.async(() -> {
                        Result<Unit, String> compRes = switchProfileUseCase.completeSwitch(prepared);
                        if (compRes.isOk()) {
                            session.setActiveProfileId(targetProfileId);
                            session.setLastDurableVersion(1L);
                            schedulerPort.onEntity(playerUuid, () -> {
                                if (player.isOnline()) {
                                    player.sendMessage(Component.text(
                                            "Successfully switched to profile " + targetProfileId.value(),
                                            NamedTextColor.GREEN));
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
     * Gracefully shuts down all active player sessions during plugin disable.
     */
    public void shutdown() {
        for (ActiveSession session : activeSessions.values()) {
            session.closeTasks();
            try {
                Player player = Bukkit.getPlayer(session.playerUuid().value());
                byte[] invBytes = new byte[0];
                if (player != null && player.isOnline()) {
                    invBytes = BukkitInventorySerializer.serializeItemStacks(
                            player.getInventory().getContents());
                }
                sessionAuthorityPort.drain(session.playerUuid(), nodeId, session.sessionEpoch());
                handoffFinalizationPort.finalizeHandoffFlush(
                        session.playerUuid(),
                        session.activeProfileId(),
                        nodeId,
                        session.sessionEpoch(),
                        session.lastDurableVersion(),
                        invBytes);
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error draining session during shutdown for " + session.playerUuid(), e);
            }
        }
        activeSessions.clear();
    }
}
