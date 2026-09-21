package com.uxplima.uxmskyblock.bukkit.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.SessionState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ActiveSessionTest {

    private static ActiveSession session() {
        return new ActiveSession(
                PlayerUuid.of(UUID.randomUUID()), ProfileId.of(UUID.randomUUID()), 1L, 0L, SessionState.ACTIVE);
    }

    @Test
    @DisplayName("A fence closes the heartbeat and the checkpoint")
    void fenceClosesBothTasks() {
        ActiveSession session = session();
        AtomicBoolean heartbeatClosed = new AtomicBoolean();
        AtomicBoolean checkpointClosed = new AtomicBoolean();
        session.attachTasks(() -> heartbeatClosed.set(true), () -> checkpointClosed.set(true));

        session.fence();

        assertThat(heartbeatClosed).isTrue();
        assertThat(checkpointClosed).isTrue();
        assertThat(session.isFenced()).isTrue();
    }

    @Test
    @DisplayName("A task attached after the fence is closed at once, not left to fire forever")
    void tasksAttachedAfterAFenceAreClosed() {
        ActiveSession session = session();
        session.fence();
        AtomicBoolean heartbeatClosed = new AtomicBoolean();
        AtomicBoolean checkpointClosed = new AtomicBoolean();

        session.attachTasks(() -> heartbeatClosed.set(true), () -> checkpointClosed.set(true));

        assertThat(heartbeatClosed)
                .describedAs("a heartbeat for a session this node no longer owns must not keep firing")
                .isTrue();
        assertThat(checkpointClosed).isTrue();
    }

    @Test
    @DisplayName("Closing the tasks twice is harmless, because a quit follows a fence")
    void closingTwiceIsHarmless() {
        ActiveSession session = session();
        AtomicBoolean closed = new AtomicBoolean();
        session.attachTasks(() -> closed.set(true), () -> {});

        session.fence();
        session.closeTasks();

        assertThat(closed).isTrue();
    }
}
