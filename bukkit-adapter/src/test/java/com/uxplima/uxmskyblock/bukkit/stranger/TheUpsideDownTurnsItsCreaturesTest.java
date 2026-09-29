package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.potion.PotionEffect;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A creature born in the Upside Down, on a StrangerRealms island's land, is turned into the one the
 * operator names, or kept from being born, and carries the operator's effects. Anywhere else, and born
 * any way the operator did not name, it is left alone.
 */
class TheUpsideDownTurnsItsCreaturesTest extends MockBukkitHarness {

    private static final String UPSIDE_DOWN = "skyblock_nether";

    private final Set<IslandId> stranger = new HashSet<>();
    private final List<String> born = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private World upsideDown;

    @SuppressWarnings("NullAway.Init")
    private UpsideDownSpawns spawns;

    @SuppressWarnings("NullAway.Init")
    private LivingEntity newborn;

    @BeforeEach
    void setUpRealms() {
        server.addSimpleWorld("realms");
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        server.addSimpleWorld("elsewhere");
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        Island strange = island(0);
        Island plain = island(1000);
        islands.cacheIsland(strange, "realms");
        islands.cacheIsland(plain, "realms");
        stranger.add(strange.id());
        StrangerRealmsService service = new StrangerRealmsService(new StrangerRealmsPort() {
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
        service.prime();
        newborn = mock(LivingEntity.class);
        spawns = new UpsideDownSpawns(
                new Realms(service, islands, () -> UPSIDE_DOWN, () -> List.of("elsewhere", "realms")),
                new StrangerRealmsConfiguration.Mobs(
                        List.of("NATURAL", "SPAWNER", "NO_SUCH_REASON"),
                        List.of("PIGLIN:ZOMBIE", "STRIDER:NONE", "BLAZE:NOT_A_CREATURE", "BAD LINE"),
                        List.of("speed:0:60", "resistance:1:60")),
                (at, type) -> {
                    born.add(type + "@" + at.getBlockX());
                    return newborn;
                });
    }

    @Test
    @DisplayName("A Nether creature born on the land is turned, and what it became carries the effects")
    void aCreatureIsTurned() {
        CreatureSpawnEvent birth = birth(EntityType.PIGLIN, upsideDown, 5, CreatureSpawnEvent.SpawnReason.NATURAL);

        spawns.onBirth(birth);

        assertThat(birth.isCancelled()).isTrue();
        assertThat(born).containsExactly("ZOMBIE@5");
        verify(newborn, times(2)).addPotionEffect(any(PotionEffect.class));
    }

    @Test
    @DisplayName(
            "A creature no rule turns is born as it is and carries the effects, and one turned into NONE is not born")
    void untouchedAndNone() {
        CreatureSpawnEvent enderman = birth(EntityType.ENDERMAN, upsideDown, 5, CreatureSpawnEvent.SpawnReason.SPAWNER);
        spawns.onBirth(enderman);
        assertThat(enderman.isCancelled()).isFalse();
        verify(enderman.getEntity(), times(2)).addPotionEffect(any(PotionEffect.class));

        CreatureSpawnEvent strider = birth(EntityType.STRIDER, upsideDown, 5, CreatureSpawnEvent.SpawnReason.NATURAL);
        spawns.onBirth(strider);
        assertThat(strider.isCancelled()).isTrue();

        CreatureSpawnEvent blaze = birth(EntityType.BLAZE, upsideDown, 5, CreatureSpawnEvent.SpawnReason.NATURAL);
        spawns.onBirth(blaze);
        assertThat(blaze.isCancelled())
                .describedAs("a rule that names no creature leaves the creature as it is")
                .isFalse();
        verify(blaze.getEntity(), times(2)).addPotionEffect(any(PotionEffect.class));
        assertThat(born).isEmpty();
    }

    @Test
    @DisplayName("Another way of being born, another world, another mode's land and no land at all are left alone")
    void elsewhereIsLeftAlone() {
        List<CreatureSpawnEvent> births = List.of(
                birth(EntityType.PIGLIN, upsideDown, 5, CreatureSpawnEvent.SpawnReason.CUSTOM),
                birth(EntityType.PIGLIN, server.getWorld("realms"), 5, CreatureSpawnEvent.SpawnReason.NATURAL),
                birth(EntityType.PIGLIN, upsideDown, 1005, CreatureSpawnEvent.SpawnReason.NATURAL),
                birth(EntityType.PIGLIN, upsideDown, 50_000, CreatureSpawnEvent.SpawnReason.NATURAL));

        for (CreatureSpawnEvent birth : births) {
            spawns.onBirth(birth);
            assertThat(birth.isCancelled()).isFalse();
            verify(birth.getEntity(), never()).addPotionEffect(any(PotionEffect.class));
        }
        assertThat(born).isEmpty();
    }

    private static CreatureSpawnEvent birth(
            EntityType type,
            @org.jspecify.annotations.Nullable World world,
            int x,
            CreatureSpawnEvent.SpawnReason reason) {
        LivingEntity creature = mock(LivingEntity.class);
        when(creature.getType()).thenReturn(type);
        when(creature.getLocation()).thenReturn(new Location(world, x + 0.5, 64, 8.5));
        return new CreatureSpawnEvent(creature, reason);
    }

    private static Island island(int centreX) {
        return Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(centreX + 8, 8, 40),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());
    }
}
