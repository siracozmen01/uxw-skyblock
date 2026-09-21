package com.uxplima.uxmskyblock.bukkit.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The friendly fire check runs on every hit of every fight. It used to ask the database for both
 * fighters' islands each time, on the thread the damage event arrives on.
 */
class IslandProtectionListenerCacheTest {

    private IslandStoragePort storagePort;
    private IslandProtectionListener listener;
    private ProfileId profileId;
    private IslandId islandId;

    @BeforeEach
    void setUp() {
        storagePort = mock(IslandStoragePort.class);
        listener = new IslandProtectionListener(storagePort, mock(IslandAccessService.class), null, null);
        profileId = new ProfileId(UUID.randomUUID());
        islandId = IslandId.of(UUID.randomUUID());
        when(storagePort.findIslandIdByProfileId(any())).thenReturn(Optional.of(islandId));
    }

    @Test
    @DisplayName("The island of a profile is asked for once and answered from memory after that")
    void theDatabaseIsAskedOnce() {
        assertThat(listener.islandOf(profileId)).contains(islandId);
        assertThat(listener.islandOf(profileId)).contains(islandId);
        assertThat(listener.islandOf(profileId)).contains(islandId);

        verify(storagePort, times(1)).findIslandIdByProfileId(profileId);
    }

    @Test
    @DisplayName("A profile with no island is remembered as having none, rather than asked again")
    void anAbsentIslandIsRememberedToo() {
        when(storagePort.findIslandIdByProfileId(any())).thenReturn(Optional.empty());

        assertThat(listener.islandOf(profileId)).isEmpty();
        assertThat(listener.islandOf(profileId)).isEmpty();

        verify(storagePort, times(1)).findIslandIdByProfileId(profileId);
    }

    @Test
    @DisplayName("Invalidating an island forgets the profiles that belonged to it")
    void invalidationForgetsTheProfile() {
        listener.islandOf(profileId);
        listener.invalidateIsland(islandId);
        listener.islandOf(profileId);

        verify(storagePort, times(2)).findIslandIdByProfileId(profileId);
    }

    @Test
    @DisplayName("A profile that leaves is forgotten, so a returning player is not answered from a stale entry")
    void leavingForgetsTheProfile() {
        PlayerUuid playerUuid = new PlayerUuid(UUID.randomUUID());
        listener.setActiveProfile(playerUuid, profileId);
        listener.islandOf(profileId);

        listener.removeActiveProfile(playerUuid);
        listener.islandOf(profileId);

        verify(storagePort, times(2)).findIslandIdByProfileId(profileId);
    }
}
