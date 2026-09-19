package com.uxplima.uxmskyblock.core.application.gamemode;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.account.PlayerAccount;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstance;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeInstanceId;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.gamemode.PrimaryGameplayRootRef;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GameModeHierarchyTest {

    private InMemoryGameModeHierarchyStoragePort storagePort;
    private GameModeHierarchyService service;

    @BeforeEach
    void setUp() {
        storagePort = new InMemoryGameModeHierarchyStoragePort();
        service = new GameModeHierarchyService(storagePort);
    }

    @Test
    @DisplayName("Canonical 4-tier hierarchy: PlayerAccount -> Profile -> GameModeInstance -> PrimaryGameplayRootRef")
    void testCanonicalHierarchyFlow() {
        // Tier 1: PlayerAccount
        PlayerUuid playerUuid = PlayerUuid.of(UUID.randomUUID());
        PlayerAccount account = PlayerAccount.create(playerUuid, Instant.now());
        assertThat(account.playerUuid()).isEqualTo(playerUuid);

        // Tier 2: Profile
        ProfileId profileId = new ProfileId(UUID.randomUUID());

        // Tier 3: GameModeInstance
        GameModeInstance instance = service.getOrCreateSkyblockInstance(profileId, "default_ruleset");
        assertThat(instance.profileId()).isEqualTo(profileId);
        assertThat(instance.gameModeType()).isEqualTo(GameModeType.SKYBLOCK);

        // Tier 4: PrimaryGameplayRootRef
        IslandId islandId = IslandId.of(UUID.randomUUID());
        service.bindIsland(instance.id(), islandId);

        Optional<PrimaryGameplayRootRef> rootRef = service.findRootRef(instance.id());
        assertThat(rootRef).isPresent();
        assertThat(rootRef.get().rootId()).isEqualTo(islandId.value().toString());
        assertThat(rootRef.get().rootType()).isEqualTo("ISLAND");
    }

    private static final class InMemoryGameModeHierarchyStoragePort implements GameModeHierarchyStoragePort {
        private final Map<GameModeInstanceId, GameModeInstance> byId = new HashMap<>();
        private final Map<ProfileId, GameModeInstance> byProfile = new HashMap<>();
        private final Map<GameModeInstanceId, PrimaryGameplayRootRef> rootsByInstance = new HashMap<>();
        private final Map<String, PrimaryGameplayRootRef> rootsByRootId = new HashMap<>();

        @Override
        public void saveGameModeInstance(GameModeInstance instance) {
            byId.put(instance.id(), instance);
            byProfile.put(instance.profileId(), instance);
        }

        @Override
        public Optional<GameModeInstance> findInstanceById(GameModeInstanceId id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<GameModeInstance> findInstanceByProfileId(ProfileId profileId) {
            return Optional.ofNullable(byProfile.get(profileId));
        }

        @Override
        public void savePrimaryGameplayRootRef(PrimaryGameplayRootRef rootRef) {
            rootsByInstance.put(rootRef.gameModeInstanceId(), rootRef);
            rootsByRootId.put(rootRef.rootId() + ":" + rootRef.rootType(), rootRef);
        }

        @Override
        public Optional<PrimaryGameplayRootRef> findRootRefByInstanceId(GameModeInstanceId instanceId) {
            return Optional.ofNullable(rootsByInstance.get(instanceId));
        }

        @Override
        public Optional<PrimaryGameplayRootRef> findRootRefByRootId(String rootId, String rootType) {
            return Optional.ofNullable(rootsByRootId.get(rootId + ":" + rootType));
        }
    }
}
