package com.uxplima.uxmskyblock.bukkit.session;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.jspecify.annotations.Nullable;

/**
 * One player's session on this server, as this server sees it.
 *
 * <p>The database holds the truth about who owns a session, and this holds what the server needs
 * between the queries: the profile in play, the epoch the server was given, the last version it
 * durably wrote, and the two repeating tasks that keep the lease alive and the inventory saved.
 *
 * <p>Every field is read from the main thread and written from a scheduler thread, so every one is
 * atomic or volatile. A fence closes both tasks and is not reversible: a session another node has
 * taken must never write again, and the way back is a new session, not a flag flip.
 */
public final class ActiveSession {
    private final PlayerUuid playerUuid;
    private volatile ProfileId activeProfileId;
    private final AtomicLong sessionEpoch;
    private final AtomicLong lastDurableVersion;
    private final AtomicReference<SessionState> state = new AtomicReference<>(SessionState.ACTIVE);
    private final AtomicReference<@Nullable AutoCloseable> heartbeatTask = new AtomicReference<>(null);
    private final AtomicReference<@Nullable AutoCloseable> checkpointTask = new AtomicReference<>(null);

    public ActiveSession(PlayerUuid playerUuid, ProfileId activeProfileId, long sessionEpoch, long lastDurableVersion) {
        this(playerUuid, activeProfileId, sessionEpoch, lastDurableVersion, SessionState.ACTIVE);
    }

    public ActiveSession(
            PlayerUuid playerUuid,
            ProfileId activeProfileId,
            long sessionEpoch,
            long lastDurableVersion,
            SessionState initialState) {
        this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        this.activeProfileId = Objects.requireNonNull(activeProfileId, "activeProfileId");
        this.sessionEpoch = new AtomicLong(sessionEpoch);
        this.lastDurableVersion = new AtomicLong(lastDurableVersion);
        this.state.set(Objects.requireNonNull(initialState, "initialState"));
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

    public boolean isFenced() {
        return state.get() == SessionState.LOCAL_FENCED;
    }

    public SessionState state() {
        SessionState s = state.get();
        return s != null ? s : SessionState.ACTIVE;
    }

    /**
     * Takes ownership of the two repeating tasks that keep this session alive.
     *
     * <p>A join runs async and a fence can land in the middle of it. A task attached after the
     * fence has closed the previous pair would keep firing forever against a session this node no
     * longer owns, so a fenced session closes them straight away instead of holding them.
     */
    public void attachTasks(AutoCloseable heartbeat, AutoCloseable checkpoint) {
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(checkpoint, "checkpoint");
        heartbeatTask.set(heartbeat);
        checkpointTask.set(checkpoint);
        if (isFenced()) {
            closeTasks();
        }
    }

    public void fence() {
        state.set(SessionState.LOCAL_FENCED);
        closeTasks();
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
