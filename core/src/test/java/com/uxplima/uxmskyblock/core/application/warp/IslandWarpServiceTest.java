package com.uxplima.uxmskyblock.core.application.warp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.warp.DuplicateWarpNameException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandBan;
import com.uxplima.uxmskyblock.core.domain.warp.IslandLockedException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarpId;
import com.uxplima.uxmskyblock.core.domain.warp.PlayerBannedFromIslandException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLimitExceededException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLockedException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import com.uxplima.uxmskyblock.core.domain.warp.WarpNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IslandWarpServiceTest {

    private InMemoryWarpStorage storagePort;
    private SafeTeleportEngine safeTeleportEngine;
    private IslandWarpService warpService;
    private DummySafeInspector inspector;

    private Island island;
    private PlayerUuid ownerUuid;
    private ProfileId ownerProfileId;
    private PlayerUuid memberUuid;
    private ProfileId memberProfileId;
    private PlayerUuid visitorUuid;
    private ProfileId visitorProfileId;

    @BeforeEach
    void setUp() {
        storagePort = new InMemoryWarpStorage();
        safeTeleportEngine = new SafeTeleportEngine(5);
        inspector = new DummySafeInspector();

        ownerUuid = PlayerUuid.of(UUID.randomUUID());
        ownerProfileId = ProfileId.of(UUID.randomUUID());
        memberUuid = PlayerUuid.of(UUID.randomUUID());
        memberProfileId = ProfileId.of(UUID.randomUUID());
        visitorUuid = PlayerUuid.of(UUID.randomUUID());
        visitorProfileId = ProfileId.of(UUID.randomUUID());

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(0, 0, 100);
        island = Island.create(IslandId.of(UUID.randomUUID()), bounds, ownerUuid, ownerProfileId, Instant.now());
        island = island.addMember(new IslandMember(memberUuid, memberProfileId, IslandRole.MEMBER, Instant.now()));

        warpService = new IslandWarpService(storagePort, safeTeleportEngine, null, 2, null);
    }

    @Test
    @DisplayName("Owner creates warp successfully within island bounds")
    void createWarpSuccess() {
        WarpLocation location = new WarpLocation("world", 10.0, 64.0, 10.0, 0.0f, 0.0f);
        IslandWarp warp = warpService.createWarp(
                island, ownerProfileId, WarpName.of("market"), location, WarpCategory.SHOPS, "CHEST");

        assertThat(warp).isNotNull();
        assertThat(warp.name().value()).isEqualTo("market");
        assertThat(warp.category()).isEqualTo(WarpCategory.SHOPS);
        assertThat(storagePort.findWarpByName(island.id(), WarpName.of("market")))
                .isPresent();
    }

    @Test
    @DisplayName("Creating warp outside island bounds is rejected")
    void createWarpOutsideBoundsRejected() {
        WarpLocation outside = new WarpLocation("world", 500.0, 64.0, 500.0, 0.0f, 0.0f);
        assertThatThrownBy(() -> warpService.createWarp(
                        island, ownerProfileId, WarpName.of("far"), outside, WarpCategory.GENERAL, "OAK_SIGN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside island bounds");
    }

    @Test
    @DisplayName("Warp limit enforcement rejects creation when limit reached")
    void warpLimitEnforced() {
        WarpLocation loc1 = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        WarpLocation loc2 = new WarpLocation("world", 6.0, 64.0, 6.0, 0.0f, 0.0f);
        WarpLocation loc3 = new WarpLocation("world", 7.0, 64.0, 7.0, 0.0f, 0.0f);

        warpService.createWarp(island, ownerProfileId, WarpName.of("w1"), loc1, WarpCategory.GENERAL, "OAK_SIGN");
        warpService.createWarp(island, ownerProfileId, WarpName.of("w2"), loc2, WarpCategory.GENERAL, "OAK_SIGN");

        assertThatThrownBy(() -> warpService.createWarp(
                        island, ownerProfileId, WarpName.of("w3"), loc3, WarpCategory.GENERAL, "OAK_SIGN"))
                .isInstanceOf(WarpLimitExceededException.class)
                .hasMessageContaining("limit (2/2)");
    }

    @Test
    @DisplayName("Duplicate warp name is rejected")
    void duplicateWarpNameRejected() {
        WarpLocation loc = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        warpService.createWarp(island, ownerProfileId, WarpName.of("shop"), loc, WarpCategory.GENERAL, "OAK_SIGN");

        assertThatThrownBy(() -> warpService.createWarp(
                        island, ownerProfileId, WarpName.of("SHOP"), loc, WarpCategory.GENERAL, "OAK_SIGN"))
                .isInstanceOf(DuplicateWarpNameException.class);
    }

    @Test
    @DisplayName("Delete warp succeeds when warp exists and user has permission")
    void deleteWarpSuccess() {
        WarpLocation loc = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        warpService.createWarp(island, ownerProfileId, WarpName.of("spawn"), loc, WarpCategory.GENERAL, "OAK_SIGN");

        warpService.deleteWarp(island, ownerProfileId, WarpName.of("spawn"));
        assertThat(storagePort.findWarpByName(island.id(), WarpName.of("spawn")))
                .isEmpty();
    }

    @Test
    @DisplayName("Delete non-existent warp throws WarpNotFoundException")
    void deleteNonExistentWarpThrows() {
        assertThatThrownBy(() -> warpService.deleteWarp(island, ownerProfileId, WarpName.of("missing")))
                .isInstanceOf(WarpNotFoundException.class);
    }

    @Test
    @DisplayName("Locking warp prevents visitor visits but allows member visits")
    void warpLockingBehavior() {
        WarpLocation loc = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        warpService.createWarp(island, ownerProfileId, WarpName.of("private"), loc, WarpCategory.GENERAL, "OAK_SIGN");
        warpService.setWarpLock(island, ownerProfileId, WarpName.of("private"), true);

        // Visitor is blocked
        assertThatThrownBy(() -> warpService.prepareVisit(
                        island, visitorUuid, visitorProfileId, WarpName.of("private"), inspector))
                .isInstanceOf(WarpLockedException.class);

        // Member is allowed
        WarpLocation destination =
                warpService.prepareVisit(island, memberUuid, memberProfileId, WarpName.of("private"), inspector);
        assertThat(destination).isNotNull();
    }

    @Test
    @DisplayName("Global island lock blocks non-members unless privileged ally")
    void islandLockBlocksNonMembers() {
        WarpLocation loc = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        warpService.createWarp(island, ownerProfileId, WarpName.of("public_w"), loc, WarpCategory.GENERAL, "OAK_SIGN");

        Island lockedIsland = island.withFlags(island.flags().withFlag(IslandFlags.LOCKED, true));

        // Visitor is blocked by global lock
        assertThatThrownBy(() -> warpService.prepareVisit(
                        lockedIsland, visitorUuid, visitorProfileId, WarpName.of("public_w"), inspector))
                .isInstanceOf(IslandLockedException.class);

        // Member is allowed
        WarpLocation dest =
                warpService.prepareVisit(lockedIsland, memberUuid, memberProfileId, WarpName.of("public_w"), inspector);
        assertThat(dest).isNotNull();
    }

    @Test
    @DisplayName("Privileged ally can visit locked island when allyChecker returns true")
    void privilegedAllyCanVisitLockedIsland() {
        IslandWarpService allyAwareService = new IslandWarpService(
                storagePort,
                safeTeleportEngine,
                null,
                2,
                (targetIslandId, profileId) -> profileId.equals(visitorProfileId));

        WarpLocation loc = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        allyAwareService.createWarp(
                island, ownerProfileId, WarpName.of("welcome"), loc, WarpCategory.GENERAL, "OAK_SIGN");

        Island lockedIsland = island.withFlags(island.flags().withFlag(IslandFlags.LOCKED, true));

        WarpLocation dest = allyAwareService.prepareVisit(
                lockedIsland, visitorUuid, visitorProfileId, WarpName.of("welcome"), inspector);
        assertThat(dest).isNotNull();
    }

    @Test
    @DisplayName("Banning player prevents warp visits and bans list is updated")
    void banningVisitorPreventsWarpVisits() {
        WarpLocation loc = new WarpLocation("world", 5.0, 64.0, 5.0, 0.0f, 0.0f);
        warpService.createWarp(island, ownerProfileId, WarpName.of("entry"), loc, WarpCategory.GENERAL, "OAK_SIGN");

        warpService.banPlayer(island, ownerProfileId, visitorUuid, "Griefing attempt");
        assertThat(warpService.isPlayerBanned(island.id(), visitorUuid)).isTrue();
        assertThat(warpService.getBans(island.id())).hasSize(1);

        assertThatThrownBy(() -> warpService.prepareVisit(
                        island, visitorUuid, visitorProfileId, WarpName.of("entry"), inspector))
                .isInstanceOf(PlayerBannedFromIslandException.class);

        // Unbanning allows visits again
        warpService.unbanPlayer(island, ownerProfileId, visitorUuid);
        assertThat(warpService.isPlayerBanned(island.id(), visitorUuid)).isFalse();

        WarpLocation dest =
                warpService.prepareVisit(island, visitorUuid, visitorProfileId, WarpName.of("entry"), inspector);
        assertThat(dest).isNotNull();
    }

    @Test
    @DisplayName("Cannot ban island owner or member")
    void cannotBanIslandMembers() {
        assertThatThrownBy(() -> warpService.banPlayer(island, ownerProfileId, ownerUuid, "Self ban"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> warpService.banPlayer(island, ownerProfileId, memberUuid, "Member ban"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class DummySafeInspector implements SafeBlockInspector {
        @Override
        public boolean isSolidFloor(String worldName, int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isPassable(String worldName, int x, int y, int z) {
            return true;
        }

        @Override
        public boolean isHazardous(String worldName, int x, int y, int z) {
            return false;
        }
    }

    private static final class InMemoryWarpStorage implements IslandWarpStoragePort {
        private final Map<IslandId, Map<WarpName, IslandWarp>> warps = new HashMap<>();
        private final Map<IslandId, Map<PlayerUuid, IslandBan>> bans = new HashMap<>();

        @Override
        public void saveWarp(IslandWarp warp) {
            warps.computeIfAbsent(warp.islandId(), k -> new HashMap<>()).put(warp.name(), warp);
        }

        @Override
        public Optional<IslandWarp> findWarpById(IslandWarpId warpId) {
            return warps.values().stream()
                    .flatMap(m -> m.values().stream())
                    .filter(w -> w.id().equals(warpId))
                    .findFirst();
        }

        @Override
        public Optional<IslandWarp> findWarpByName(IslandId islandId, WarpName warpName) {
            Map<WarpName, IslandWarp> m = warps.get(islandId);
            return m != null ? Optional.ofNullable(m.get(warpName)) : Optional.empty();
        }

        @Override
        public List<IslandWarp> findWarpsByIsland(IslandId islandId) {
            Map<WarpName, IslandWarp> m = warps.get(islandId);
            return m != null ? new ArrayList<>(m.values()) : List.of();
        }

        @Override
        public List<IslandWarp> findPublicWarps(int limit, int offset) {
            return warps.values().stream()
                    .flatMap(m -> m.values().stream())
                    .filter(w -> !w.isLocked())
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<IslandWarp> findPublicWarpsByCategory(WarpCategory category, int limit, int offset) {
            return warps.values().stream()
                    .flatMap(m -> m.values().stream())
                    .filter(w -> !w.isLocked() && w.category() == category)
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }

        @Override
        public int countWarpsByIsland(IslandId islandId) {
            Map<WarpName, IslandWarp> m = warps.get(islandId);
            return m != null ? m.size() : 0;
        }

        @Override
        public boolean deleteWarp(IslandId islandId, WarpName warpName) {
            Map<WarpName, IslandWarp> m = warps.get(islandId);
            return m != null && m.remove(warpName) != null;
        }

        @Override
        public void banPlayer(IslandBan ban) {
            bans.computeIfAbsent(ban.islandId(), k -> new HashMap<>()).put(ban.bannedPlayerUuid(), ban);
        }

        @Override
        public boolean unbanPlayer(IslandId islandId, PlayerUuid playerUuid) {
            Map<PlayerUuid, IslandBan> m = bans.get(islandId);
            return m != null && m.remove(playerUuid) != null;
        }

        @Override
        public boolean isPlayerBanned(IslandId islandId, PlayerUuid playerUuid) {
            Map<PlayerUuid, IslandBan> m = bans.get(islandId);
            return m != null && m.containsKey(playerUuid);
        }

        @Override
        public List<IslandBan> findBansByIsland(IslandId islandId) {
            Map<PlayerUuid, IslandBan> m = bans.get(islandId);
            return m != null ? new ArrayList<>(m.values()) : List.of();
        }
    }
}
