package com.uxplima.uxmskyblock.core.application.home;

import java.util.List;
import java.util.Optional;

import com.uxplima.uxmskyblock.core.domain.home.Home;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * Storage port for persistent player and island homes (Section 2.42).
 */
public interface HomeStoragePort {

    void saveHome(Home home);

    Optional<Home> findHome(ProfileId profileId, String name);

    List<Home> findHomesByProfileId(ProfileId profileId);

    List<Home> findHomesByIslandId(IslandId islandId);

    boolean deleteHome(ProfileId profileId, String name);

    int countHomes(ProfileId profileId);
}
