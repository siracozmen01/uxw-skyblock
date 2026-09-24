package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.SessionLease;

/**
 * A player's session given to the next server before the proxy moves them, and taken back when the
 * proxy never did.
 */
final class ServerHandoff {

    private static final Logger LOGGER = Logger.getLogger(ServerHandoff.class.getName());

    private final ServerNodeId nodeId;
    private final PlayerSessionAuthorityPort sessionAuthorityPort;
    private final ProfileHandoffFinalizationPort handoffFinalizationPort;
    private final SchedulerPort schedulerPort;
    private final IslandProtectionListener protectionListener;
    private final LongSupplier nanoClock;
    private final ConcurrentMap<UUID, ActiveSession> activeSessions;
    private final BiConsumer<PlayerUuid, String> selfFence;
    private final Consumer<Player> rejoin;

    ServerHandoff(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SchedulerPort schedulerPort,
            IslandProtectionListener protectionListener,
            LongSupplier nanoClock,
            ConcurrentMap<UUID, ActiveSession> activeSessions,
            BiConsumer<PlayerUuid, String> selfFence,
            Consumer<Player> rejoin) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sessionAuthorityPort = Objects.requireNonNull(sessionAuthorityPort, "sessionAuthorityPort");
        this.handoffFinalizationPort = Objects.requireNonNull(handoffFinalizationPort, "handoffFinalizationPort");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.activeSessions = Objects.requireNonNull(activeSessions, "activeSessions");
        this.selfFence = Objects.requireNonNull(selfFence, "selfFence");
        this.rejoin = Objects.requireNonNull(rejoin, "rejoin");
    }

    /**
     * Hands the player's session to {@code target} before the proxy moves them there.
     *
     * <p>A move between servers used to leave the session where it was: the next server found it
     * still held, and the player's last state was written by the quit on this one whenever that came.
     * The state is now written here, whole, before the move, and the session is readied for the
     * target, which takes it at the next epoch as the player arrives. Nothing the player does between
     * this and the move goes through.
     *
     * <p>A session that cannot be drained is left as it was and the player stays; the answer is false
     * and nobody is moved. Once drained, a session whose state cannot be written, or whose handoff
     * cannot be readied, is fenced and the player disconnected: writing it later could overwrite what
     * the target writes. A player the proxy never moves gets the session back here once the handoff
     * has lapsed.
     */
    java.util.concurrent.CompletableFuture<Boolean> handOff(Player player, ServerNodeId target) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target");
        java.util.concurrent.CompletableFuture<Boolean> done = new java.util.concurrent.CompletableFuture<>();
        ActiveSession session = activeSessions.get(player.getUniqueId());
        if (session == null || target.equals(nodeId)) {
            done.complete(false);
            return done;
        }
        schedulerPort.onEntity(session.playerUuid(), () -> {
            if (!player.isOnline() || !session.inPlay(nanoClock.getAsLong())) {
                done.complete(false);
                return;
            }
            session.leavePlay();
            ProfileInventoryRecord state = BukkitInventorySerializer.snapshotPlayer(
                    player, session.activeProfileId(), session.lastDurableVersion());
            schedulerPort.async(() -> done.complete(handOffDurably(player, session, state, target)));
        });
        return done;
    }

    private boolean handOffDurably(
            Player player, ActiveSession session, ProfileInventoryRecord state, ServerNodeId target) {
        PlayerUuid playerUuid = session.playerUuid();
        try {
            if (!sessionAuthorityPort
                    .drain(playerUuid, nodeId, session.sessionEpoch())
                    .isSuccess()) {
                session.enterPlay();
                return false;
            }
            session.closeTasks();
            ProfileInventoryMutationOutcome written = handoffFinalizationPort.finalizeHandoffFlush(
                    playerUuid,
                    session.activeProfileId(),
                    nodeId,
                    session.sessionEpoch(),
                    session.lastDurableVersion(),
                    state);
            if (!(written instanceof ProfileInventoryMutationOutcome.Success succ)) {
                selfFence.accept(playerUuid, "The state could not be written before a move: " + written);
                return false;
            }
            session.setLastDurableVersion(succ.newVersion());
            SessionAuthorityOutcome readied = sessionAuthorityPort.prepareHandoff(
                    playerUuid,
                    nodeId,
                    session.sessionEpoch(),
                    UUID.randomUUID().toString(),
                    target);
            if (!readied.isSuccess()) {
                selfFence.accept(playerUuid, "The handoff to " + target.value() + " could not be readied: " + readied);
                return false;
            }
        } catch (RuntimeException e) {
            LOGGER.log(Level.SEVERE, "Handing " + playerUuid.value() + " to " + target.value() + " failed", e);
            selfFence.accept(playerUuid, "The handoff failed: " + e.getMessage());
            return false;
        }
        activeSessions.remove(playerUuid.value(), session);
        protectionListener.removeActiveProfile(playerUuid);
        schedulerPort.asyncAfter(
                SessionLease.HANDOFF.plus(SessionLease.SAFETY_MARGIN), () -> reclaimIfStillHere(player));
        return true;
    }

    /**
     * Takes the session back for a player the proxy did not move, once the handoff has lapsed: the
     * target never took it, and the state it would have loaded is the one written here.
     */
    void reclaimIfStillHere(Player player) {
        if (player.isOnline() && !activeSessions.containsKey(player.getUniqueId())) {
            rejoin.accept(player);
        }
    }
}
