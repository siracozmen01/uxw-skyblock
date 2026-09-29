package com.uxplima.uxmskyblock.core.application.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandMember;
import com.uxplima.uxmskyblock.core.domain.island.IslandRole;
import com.uxplima.uxmskyblock.core.domain.stranger.ClaimGrowth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A StrangerRealms island reaches as far as its members take it, and every other island as its size. */
class StrangerClaimsTest {

    private final Set<IslandId> stranger = new HashSet<>();
    private final IslandStoragePort islands = mock(IslandStoragePort.class);
    private final StrangerRealmsService service = new StrangerRealmsService(new StrangerRealmsPort() {
        @Override
        public Set<IslandId> findAll() {
            return Set.copyOf(stranger);
        }

        @Override
        public boolean exists(IslandId islandId) {
            return stranger.contains(islandId);
        }

        @Override
        public void add(IslandId islandId) {
            stranger.add(islandId);
        }

        @Override
        public int farthestReach() {
            return 0;
        }
    });

    @Test
    @DisplayName(
            "Three members take a StrangerRealms island two shares past its size, and another island stays its size")
    void membersTakeItFurther() {
        Island team = island(3);
        Island plain = island(3);
        service.start(team.id());
        when(islands.findIslandById(team.id())).thenReturn(Optional.of(team));
        when(islands.findIslandById(plain.id())).thenReturn(Optional.of(plain));
        StrangerClaims claims = new StrangerClaims(service, islands, new ClaimGrowth(10, 200));

        assertThat(claims.radius(team.id(), 25)).isEqualTo(45);
        assertThat(claims.radius(plain.id(), 25)).isEqualTo(25);
        assertThat(claims.radius(IslandId.of(UUID.randomUUID()), 25)).isEqualTo(25);
    }

    @Test
    @DisplayName("A StrangerRealms island that cannot be read reaches what its size gives")
    void anUnreadIslandIsItsSize() {
        IslandId gone = IslandId.of(UUID.randomUUID());
        service.start(gone);
        when(islands.findIslandById(gone)).thenReturn(Optional.empty());

        assertThat(new StrangerClaims(service, islands, new ClaimGrowth(10, 200)).radius(gone, 25))
                .isEqualTo(25);
    }

    private static Island island(int members) {
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 25),
                PlayerUuid.of(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
        for (int added = 1; added < members; added++) {
            island = island.addMember(new IslandMember(
                    PlayerUuid.of(UUID.randomUUID()),
                    new ProfileId(UUID.randomUUID()),
                    IslandRole.MEMBER,
                    Instant.now()));
        }
        return island;
    }
}
