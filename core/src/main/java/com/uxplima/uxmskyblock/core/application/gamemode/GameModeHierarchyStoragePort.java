package com.uxplima.uxmskyblock.core.application.gamemode;

import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Storage port interface for persisting and querying GameModeInstances and primary root bindings.
 */
public interface GameModeHierarchyStoragePort {

    void saveGameModeInstance(GameModeInstance instance);

    Optional<GameModeInstance> findInstanceById(GameModeInstanceId id);

    Optional<GameModeInstance> findInstanceByProfileId(ProfileId profileId);

    void savePrimaryGameplayRootRef(PrimaryGameplayRootRef rootRef);

    Optional<PrimaryGameplayRootRef> findRootRefByInstanceId(GameModeInstanceId instanceId);

    Optional<PrimaryGameplayRootRef> findRootRefByRootId(String rootId, String rootType);
}
