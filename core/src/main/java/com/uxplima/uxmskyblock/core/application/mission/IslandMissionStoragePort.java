package com.uxplima.uxmskyblock.core.application.mission;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;

public interface IslandMissionStoragePort {

    Optional<MissionProgress> findProgress(IslandId islandId, ProfileId profileId, MissionId missionId);

    Map<MissionId, MissionProgress> findAllProgress(IslandId islandId, ProfileId profileId);

    void saveProgress(IslandId islandId, ProfileId profileId, MissionProgress progress);

    void saveAllProgress(IslandId islandId, ProfileId profileId, Collection<MissionProgress> progresses);
}
