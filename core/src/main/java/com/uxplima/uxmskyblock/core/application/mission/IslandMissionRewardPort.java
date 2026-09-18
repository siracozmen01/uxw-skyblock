package com.uxplima.uxmskyblock.core.application.mission;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;

@FunctionalInterface
public interface IslandMissionRewardPort {
    void dispatchReward(IslandId islandId, ProfileId profileId, MissionDefinition mission);
}
