package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.core.application.inventory.ProfileHandoffFinalizationPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.session.PlayerSessionAuthorityPort;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryMutationOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.ProfileInventoryRecord;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.session.SessionAuthorityOutcome;

/**
 * What a stop does with every session still open: write what each player holds and let it go.
 */
final class SessionShutdown {

    private static final Logger LOGGER = Logger.getLogger(SessionShutdown.class.getName());

    private final ServerNodeId nodeId;
    private final PlayerSessionAuthorityPort sessionAuthorityPort;
    private final ProfileHandoffFinalizationPort handoffFinalizationPort;
    private final SchedulerPort schedulerPort;

    SessionShutdown(
            ServerNodeId nodeId,
            PlayerSessionAuthorityPort sessionAuthorityPort,
            ProfileHandoffFinalizationPort handoffFinalizationPort,
            SchedulerPort schedulerPort) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.sessionAuthorityPort = Objects.requireNonNull(sessionAuthorityPort, "sessionAuthorityPort");
        this.handoffFinalizationPort = Objects.requireNonNull(handoffFinalizationPort, "handoffFinalizationPort");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
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

    /** Writes and releases every session in {@code sessions}, the way a stop must. */
    void drain(Collection<ActiveSession> sessions) {
        for (ActiveSession session : sessions) {
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
    }
}
