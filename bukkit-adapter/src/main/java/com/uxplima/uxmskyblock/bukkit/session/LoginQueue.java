package com.uxplima.uxmskyblock.bukkit.session;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.SessionLease;

/** Players whose session another server still holds, waiting for it rather than being turned away. */
final class LoginQueue {

    /** Asks for a waiting player's session again. */
    @FunctionalInterface
    interface Retry {
        void retry(Player player, long queuedAt, boolean told);
    }

    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final LongSupplier nanoClock;
    private final Retry retry;

    LoginQueue(SchedulerPort schedulerPort, Messages messages, LongSupplier nanoClock, Retry retry) {
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    /**
     * How long a player may wait for their session to leave another server: the longest a lease
     * another node holds can outlive that node, and the margin a renewal may still be in flight.
     */
    static final Duration LOGIN_WAIT = SessionLease.HANDOFF.plus(SessionLease.SAFETY_MARGIN);

    /** How often a waiting player's session is asked for again. */
    static final Duration LOGIN_RETRY = Duration.ofSeconds(1);

    /**
     * A player whose session another server still holds waits for it, and is not turned away.
     *
     * <p>A proxy connects the player to the next server before the last one has let the session go,
     * so a move between servers arrived while the old server was still writing the player's state,
     * and the player was kicked as though two servers wanted them. The session is asked for again
     * every {@link #LOGIN_RETRY} until the other server releases it or its lease runs out; the player
     * is told once, and turned away only when {@link #LOGIN_WAIT} has passed.
     */
    void waitOrRefuse(Player player, long queuedAt, boolean told) {
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        if (!player.isOnline()) {
            return;
        }
        if (nanoClock.getAsLong() - queuedAt >= LOGIN_WAIT.toNanos()) {
            schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    player.kick(messages.render(player, "session.authority_refused"));
                }
            });
            return;
        }
        if (!told) {
            schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    messages.send(player, "session.waiting_elsewhere");
                }
            });
        }
        schedulerPort.asyncAfter(LOGIN_RETRY, () -> {
            if (player.isOnline()) {
                retry.retry(player, queuedAt, true);
            }
        });
    }
}
