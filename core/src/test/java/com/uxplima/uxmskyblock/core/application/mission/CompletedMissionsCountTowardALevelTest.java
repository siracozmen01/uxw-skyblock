package com.uxplima.uxmskyblock.core.application.mission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finished missions are counted, because the island level weighs them.
 *
 * <p>{@code levels.quest-weight} is a number the operator sets, and the command that shows a level
 * passed a hardcoded zero in its place. A player who finished every mission on the server scored
 * exactly the same as one who finished none, and the setting that was supposed to change that could
 * not be reached from anywhere.
 */
class CompletedMissionsCountTowardALevelTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    private static MissionProgress progress(String id, boolean completed) {
        return new MissionProgress(
                MissionId.of(id), completed ? 10L : 3L, completed, completed ? Instant.now() : null, Instant.now());
    }

    private static IslandMissionService serviceWith(Map<MissionId, MissionProgress> stored) {
        IslandMissionStoragePort port = mock(IslandMissionStoragePort.class);
        when(port.findAllProgress(ISLAND, PROFILE)).thenReturn(stored);
        return new IslandMissionService(port);
    }

    @Test
    @DisplayName("Only the finished ones are counted")
    void onlyFinishedMissionsCount() {
        Map<MissionId, MissionProgress> stored = new LinkedHashMap<>();
        stored.put(MissionId.of("mine_stone"), progress("mine_stone", true));
        stored.put(MissionId.of("catch_fish"), progress("catch_fish", false));
        stored.put(MissionId.of("kill_zombie"), progress("kill_zombie", true));

        assertThat(serviceWith(stored).countCompleted(ISLAND, PROFILE)).isEqualTo(2);
    }

    @Test
    @DisplayName("A player who has finished nothing counts zero, not an error")
    void nothingFinishedIsZero() {
        assertThat(serviceWith(Map.of()).countCompleted(ISLAND, PROFILE)).isZero();
    }

    @Test
    @DisplayName("Every mission finished counts every mission")
    void allFinishedCountsAll() {
        Map<MissionId, MissionProgress> stored = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            stored.put(MissionId.of("mission_" + i), progress("mission_" + i, true));
        }

        assertThat(serviceWith(stored).countCompleted(ISLAND, PROFILE)).isEqualTo(7);
    }
}
