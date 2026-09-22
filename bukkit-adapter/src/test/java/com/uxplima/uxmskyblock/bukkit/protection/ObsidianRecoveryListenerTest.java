package com.uxplima.uxmskyblock.bukkit.protection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"deprecation", "removal"})
class ObsidianRecoveryListenerTest extends MockBukkitHarness {

    private World world;
    private PlayerMock player;
    private ProtectionConfiguration config;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("skyblock_world");
        player = createPlayer("Steve");
        config = ProtectionConfiguration.defaultConfiguration();
    }

    @Test
    @DisplayName("Recovers accidental obsidian into lava bucket within time window")
    void recoversAccidentalObsidian() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        ObsidianRecoveryListener listener = new ObsidianRecoveryListener(config, fixedClock);

        Block block = world.getBlockAt(10, 64, 10);
        block.setType(Material.OBSIDIAN);

        BlockState newState = mock(BlockState.class);
        when(newState.getType()).thenReturn(Material.OBSIDIAN);

        BlockFormEvent formEvent = new BlockFormEvent(block, newState);
        listener.onBlockForm(formEvent);

        player.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));

        PlayerInteractEvent interactEvent = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(),
                block,
                null,
                EquipmentSlot.HAND);
        listener.onPlayerInteract(interactEvent);

        assertThat(interactEvent.isCancelled()).isTrue();
        assertThat(block.getType()).isEqualTo(Material.AIR);
        assertThat(player.getInventory().getItemInMainHand().getType()).isEqualTo(Material.LAVA_BUCKET);
    }

    @Test
    @DisplayName("Does not recover obsidian if recovery window expired")
    void doesNotRecoverExpiredObsidian() {
        Instant t0 = Instant.parse("2026-09-19T12:00:00Z");
        MutableClock mutableClock = new MutableClock(t0, ZoneOffset.UTC);
        ObsidianRecoveryListener listener = new ObsidianRecoveryListener(config, mutableClock);

        Block block = world.getBlockAt(10, 64, 10);
        block.setType(Material.OBSIDIAN);

        BlockState newState = mock(BlockState.class);
        when(newState.getType()).thenReturn(Material.OBSIDIAN);
        listener.onBlockForm(new BlockFormEvent(block, newState));

        // Advance clock past 60s window
        mutableClock.advance(java.time.Duration.ofSeconds(65));

        player.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));
        PlayerInteractEvent interactEvent = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(),
                block,
                null,
                EquipmentSlot.HAND);
        listener.onPlayerInteract(interactEvent);

        assertThat(interactEvent.isCancelled()).isFalse();
        assertThat(player.getInventory().getItemInMainHand().getType()).isEqualTo(Material.BUCKET);
    }

    @Test
    @DisplayName("An accident whose window has run out is forgotten when the next one forms")
    void anExpiredAccidentIsForgotten() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        ObsidianRecoveryListener listener = new ObsidianRecoveryListener(config, clock);
        BlockState obsidian = mock(BlockState.class);
        when(obsidian.getType()).thenReturn(Material.OBSIDIAN);

        for (int x = 0; x < 5; x++) {
            listener.onBlockForm(new BlockFormEvent(world.getBlockAt(x, 64, 0), obsidian));
        }
        clock.advance(java.time.Duration.ofSeconds(65));
        listener.onBlockForm(new BlockFormEvent(world.getBlockAt(20, 64, 0), obsidian));

        assertThat(listener.remembered())
                .describedAs("accidents still inside their window")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Nothing is remembered when every obsidian block may be recovered")
    void nothingIsRememberedWhenEveryBlockMayBeRecovered() {
        ProtectionConfiguration anyObsidian = new ProtectionConfiguration(
                config.obsidianRecoveryEnabled(),
                false,
                config.obsidianRecoveryExpiration(),
                config.voidRecoveryEnabled(),
                config.voidRecoveryThresholdY(),
                config.voidRecoveryFallDamageShield(),
                config.kineticWardEnabled(),
                config.kineticWardRadius(),
                config.kineticWardForce(),
                config.kineticWardVerticalLift());
        ObsidianRecoveryListener listener = new ObsidianRecoveryListener(anyObsidian, Clock.systemUTC());
        BlockState obsidian = mock(BlockState.class);
        when(obsidian.getType()).thenReturn(Material.OBSIDIAN);

        listener.onBlockForm(new BlockFormEvent(world.getBlockAt(0, 64, 0), obsidian));

        assertThat(listener.remembered()).isZero();
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final java.time.ZoneId zone;

        MutableClock(Instant instant, java.time.ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(java.time.Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    @DisplayName("Ignores non-obsidian blocks or interaction without bucket")
    void ignoresNonObsidianOrWithoutBucket() {
        Clock fixedClock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        ObsidianRecoveryListener listener = new ObsidianRecoveryListener(config, fixedClock);

        Block block = world.getBlockAt(10, 64, 10);
        block.setType(Material.COBBLESTONE);

        player.getInventory().setItemInMainHand(new ItemStack(Material.BUCKET));

        PlayerInteractEvent interactEvent = new PlayerInteractEvent(
                player,
                Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(),
                block,
                null,
                EquipmentSlot.HAND);
        listener.onPlayerInteract(interactEvent);

        assertThat(interactEvent.isCancelled()).isFalse();
        assertThat(block.getType()).isEqualTo(Material.COBBLESTONE);
    }
}
