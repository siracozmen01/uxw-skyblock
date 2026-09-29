package com.uxplima.uxmskyblock.bukkit.stranger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.CompassMeta;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.StrangerRealmsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandAccessService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsPort;
import com.uxplima.uxmskyblock.core.application.stranger.StrangerRealmsService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The warped compass, held on a StrangerRealms island, points at the nearest player on the other side
 * of the veil of the same island, at the same place on the holder's side, and spins with nobody there.
 */
class TheWarpedCompassPointsAcrossTest extends MockBukkitHarness {

    private static final String UPSIDE_DOWN = "skyblock_nether";

    private final Set<IslandId> stranger = new HashSet<>();
    private final Messages messages = Messages.bundled();

    @SuppressWarnings("NullAway.Init")
    private World land;

    @SuppressWarnings("NullAway.Init")
    private World upsideDown;

    @SuppressWarnings("NullAway.Init")
    private WarpedCompass compass;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock holder;

    @BeforeEach
    void setUpRealms() {
        land = server.addSimpleWorld("realms");
        upsideDown = server.addSimpleWorld(UPSIDE_DOWN);
        IslandProtectionListener islands =
                new IslandProtectionListener(mock(IslandStoragePort.class), new IslandAccessService());
        Island near = island(0);
        Island far = island(1000);
        islands.cacheIsland(near, "realms");
        islands.cacheIsland(far, "realms");
        stranger.add(near.id());
        stranger.add(far.id());
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
        });
        service.prime();
        compass = new WarpedCompass(
                new Realms(service, islands, () -> UPSIDE_DOWN, () -> List.of("realms")),
                mock(SchedulerPort.class),
                messages,
                StrangerRealmsConfiguration.Compass.SHIPPED);
        holder = player("Holder", land, 8, 8);
        holder.getInventory().setItemInMainHand(WarpedCompass.compass());
    }

    @Test
    @DisplayName("The compass points at the nearest player across the veil, at their place on this side")
    void itPointsAcross() {
        seen("OtherIsland", upsideDown, 1010, 8);
        seen("SameSide", land, 9, 9);
        assertThat(compass.check(holder))
                .describedAs("another island's player and one on the same side are not followed")
                .isEmpty();

        PlayerMock farAcross = seen("FarAcross", upsideDown, 30, 30);
        assertThat(compass.check(holder)).hasValueSatisfying(target -> at(target, land, 30, 30));
        assertThat(lodestone(holder.getInventory().getItemInMainHand()))
                .hasValueSatisfying(target -> at(target, land, 30, 30));

        seen("NearAcross", upsideDown, 12, 10);
        assertThat(compass.check(holder)).hasValueSatisfying(target -> at(target, land, 12, 10));
        assertThat(farAcross.getName()).isEqualTo("FarAcross");
    }

    @Test
    @DisplayName("From the Upside Down it points at the land, and one who leaves the island is no longer followed")
    void itFollowsOnlyTheIsland() {
        PlayerMock below = player("Below", upsideDown, 8, 8);
        below.getInventory().setItemInOffHand(WarpedCompass.compass());
        PlayerMock walker = seen("Walker", land, 20, 20);

        assertThat(compass.check(below)).hasValueSatisfying(target -> at(target, upsideDown, 20, 20));
        assertThat(lodestone(below.getInventory().getItemInOffHand()))
                .hasValueSatisfying(target -> at(target, upsideDown, 20, 20));

        walker.teleport(new Location(land, 50_000.5, 64, 8.5));
        compass.check(walker);
        assertThat(compass.check(below)).isEmpty();
        assertThat(lodestone(below.getInventory().getItemInOffHand()))
                .describedAs("with nobody across the compass spins")
                .isEmpty();
    }

    @Test
    @DisplayName("Without a warped compass in hand nothing turns, and a plain compass is no warped one")
    void onlyTheWarpedCompassTurns() {
        seen("Across", upsideDown, 20, 20);
        holder.getInventory().setItemInMainHand(new ItemStack(Material.COMPASS));

        assertThat(compass.check(holder)).isEmpty();
        assertThat(lodestone(holder.getInventory().getItemInMainHand())).isEmpty();
        assertThat(WarpedCompass.isWarped(new ItemStack(Material.COMPASS))).isFalse();
        assertThat(WarpedCompass.isWarped(WarpedCompass.compass())).isTrue();
    }

    @Test
    @DisplayName(
            "The operator's ingredients make one, an item that is none makes nothing, and it is named for its maker")
    void itIsMadeAndNamed() {
        Optional<ShapelessRecipe> recipe = compass.recipe();
        assertThat(recipe).hasValueSatisfying(made -> {
            assertThat(WarpedCompass.isWarped(made.getResult())).isTrue();
            assertThat(made.getChoiceList()).hasSize(2);
        });
        WarpedCompass broken = new WarpedCompass(
                mock(Realms.class),
                mock(SchedulerPort.class),
                messages,
                new StrangerRealmsConfiguration.Compass(
                        true, Duration.ofSeconds(1), List.of("COMPASS", "NOT_AN_ITEM")));
        assertThat(broken.recipe()).isEmpty();
        WarpedCompass unheld = new WarpedCompass(
                mock(Realms.class),
                mock(SchedulerPort.class),
                messages,
                new StrangerRealmsConfiguration.Compass(true, Duration.ofSeconds(1), List.of("COMPASS", "WATER")));
        assertThat(unheld.recipe())
                .describedAs("water is a block nobody holds, so it makes nothing")
                .isEmpty();

        CraftingInventory grid = mock(CraftingInventory.class);
        when(grid.getResult()).thenReturn(WarpedCompass.compass());
        when(grid.getViewers()).thenReturn(List.of(holder));
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(grid);
        compass.onPrepare(new PrepareItemCraftEvent(grid, view, false));

        org.mockito.ArgumentCaptor<ItemStack> result = org.mockito.ArgumentCaptor.forClass(ItemStack.class);
        verify(grid).setResult(result.capture());
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(result.getValue().getItemMeta().itemName()))
                .isEqualTo("Warped Compass");
        assertThat(result.getValue().getItemMeta().lore()).hasSize(2);
        assertThat(WarpedCompass.isWarped(result.getValue())).isTrue();
    }

    private PlayerMock seen(String name, World world, int x, int z) {
        PlayerMock player = player(name, world, x, z);
        compass.check(player);
        return player;
    }

    private PlayerMock player(String name, World world, int x, int z) {
        PlayerMock player = createPlayer(name);
        player.teleport(new Location(world, x + 0.5, 64, z + 0.5));
        return player;
    }

    private static void at(Location target, World world, int x, int z) {
        assertThat(target.getWorld()).isEqualTo(world);
        assertThat(target.getBlockX()).isEqualTo(x);
        assertThat(target.getBlockZ()).isEqualTo(z);
    }

    private static Optional<Location> lodestone(@Nullable ItemStack item) {
        return item != null && item.getItemMeta() instanceof CompassMeta meta
                ? Optional.ofNullable(meta.getLodestone())
                : Optional.empty();
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
