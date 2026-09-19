package com.uxplima.uxmskyblock.core.application.name;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandNameServiceTest {

    private IslandNameStoragePort mockStorage;
    private IslandStoragePort mockIslandStorage;
    private IslandAccessService accessService;
    private OutboxPort mockOutbox;
    private IslandNameService nameService;

    private IslandId islandId;
    private ProfileId ownerProfileId;
    private ProfileId visitorProfileId;
    private Island testIsland;

    @BeforeEach
    void setUp() {
        mockStorage = mock(IslandNameStoragePort.class);
        mockIslandStorage = mock(IslandStoragePort.class);
        mockOutbox = mock(OutboxPort.class);
        accessService = new IslandAccessService();

        islandId = new IslandId(UUID.randomUUID());
        ownerProfileId = new ProfileId(UUID.randomUUID());
        visitorProfileId = new ProfileId(UUID.randomUUID());

        testIsland = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(ownerProfileId.value()),
                ownerProfileId,
                Instant.parse("2026-09-19T12:00:00Z"));

        when(mockIslandStorage.findIslandById(islandId)).thenReturn(Optional.of(testIsland));

        nameService = new IslandNameService(
                mockStorage, mockIslandStorage, accessService, mockOutbox, Set.of("vulgarity", "offensive"));
    }

    @Test
    @DisplayName("Successfully renames island when authorized owner and name is valid")
    void renameIslandSuccess() {
        when(mockStorage.findIslandIdByName("SkyCitadel")).thenReturn(Optional.empty());

        IslandName result = nameService.renameIsland(islandId, ownerProfileId, "SkyCitadel");

        assertThat(result.value()).isEqualTo("SkyCitadel");
        verify(mockStorage).updateCustomName(islandId, result);
        verify(mockOutbox)
                .stageEvent(any(), eq("ISLAND_RENAMED"), eq(islandId.value().toString()), any());
    }

    @Test
    @DisplayName("Rejects renaming when visitor lacks SETTINGS_MODIFY permission")
    void renameFailsWithoutPermission() {
        assertThatThrownBy(() -> nameService.renameIsland(islandId, visitorProfileId, "SkyCitadel"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Rejects invalid names: too short, too long, or invalid characters")
    void renameFailsInvalidName() {
        // Too short (< 3)
        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "ab"))
                .isInstanceOf(IllegalArgumentException.class);

        // Too long (> 16)
        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "ThisNameIsTooLong12345"))
                .isInstanceOf(IllegalArgumentException.class);

        // Invalid characters
        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "Sky*Citadel!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects reserved system keywords")
    void renameFailsReservedKeyword() {
        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "Spawn"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects prohibited words matching profanity filter")
    void renameFailsProfanity() {
        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "My_Vulgarity"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Rejects duplicate name if already claimed by another island")
    void renameFailsAlreadyTaken() {
        IslandId otherIsland = new IslandId(UUID.randomUUID());
        when(mockStorage.findIslandIdByName("TakenName")).thenReturn(Optional.of(otherIsland));

        assertThatThrownBy(() -> nameService.renameIsland(islandId, ownerProfileId, "TakenName"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already taken");
    }

    @Test
    @DisplayName("Allows keeping current island name on update")
    void renamePermitsSameIslandName() {
        when(mockStorage.findIslandIdByName("CurrentName")).thenReturn(Optional.of(islandId));

        IslandName result = nameService.renameIsland(islandId, ownerProfileId, "CurrentName");
        assertThat(result.value()).isEqualTo("CurrentName");
    }

    @Test
    @DisplayName("Successfully resets island name to default")
    void resetIslandNameSuccess() {
        nameService.resetIslandName(islandId, ownerProfileId);

        verify(mockStorage).updateCustomName(islandId, null);
        verify(mockOutbox)
                .stageEvent(any(), eq("ISLAND_NAME_RESET"), eq(islandId.value().toString()), any());
    }
}
