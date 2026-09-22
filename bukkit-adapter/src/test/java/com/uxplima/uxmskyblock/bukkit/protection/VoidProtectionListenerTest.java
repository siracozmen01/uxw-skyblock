package com.uxplima.uxmskyblock.bukkit.protection;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.util.Vector;

import com.uxplima.uxmskyblock.bukkit.config.ProtectionConfiguration;
import com.uxplima.uxmskyblock.bukkit.config.SettingsConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"deprecation", "removal"})
class VoidProtectionListenerTest extends MockBukkitHarness {

    private PlayerMock player;
    private Island island;
    private ProtectionConfiguration protectionConfig;
    private SettingsConfiguration settingsConfig;

    @BeforeEach
    void setUp() {
        server.addSimpleWorld("skyblock_world");
        player = createRegionThreadedPlayer("Alex");
        protectionConfig = ProtectionConfiguration.defaultConfiguration();
        settingsConfig = SettingsConfiguration.defaultConfiguration();

        island = Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(100, 100, 50),
                new PlayerUuid(player.getUniqueId()),
                new ProfileId(player.getUniqueId()),
                Instant.now());
    }

    @Test
    @DisplayName("Intercepts void damage, cancels it, resets velocity and teleports to safety")
    void interceptsVoidDamage() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        VoidProtectionListener listener = new VoidProtectionListener(
                protectionConfig, settingsConfig, loc -> Optional.of(island), clock, Messages.bundled());

        player.setVelocity(new Vector(0, -5, 0));
        player.setFallDistance(25.0f);

        EntityDamageEvent voidDamageEvent = new EntityDamageEvent(player, DamageCause.VOID, 10.0);
        listener.onVoidDamage(voidDamageEvent);

        assertThat(voidDamageEvent.isCancelled()).isTrue();
        assertThat(player.getVelocity()).isEqualTo(new Vector(0, 0, 0));
        assertThat(player.getFallDistance()).isZero();
        // Teleported to island center: 100, 65, 100
        assertThat(player.getLocation().getBlockX()).isEqualTo(100);
        assertThat(player.getLocation().getBlockZ()).isEqualTo(100);
    }

    @Test
    @DisplayName("Shields player from fall damage within 10-second post-void window")
    void shieldsFallDamageAfterVoid() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        VoidProtectionListener listener = new VoidProtectionListener(
                protectionConfig, settingsConfig, loc -> Optional.of(island), clock, Messages.bundled());

        // Trigger void recovery
        EntityDamageEvent voidDamage = new EntityDamageEvent(player, DamageCause.VOID, 10.0);
        listener.onVoidDamage(voidDamage);

        // 5 seconds later: fall damage event occurs
        clock.advance(java.time.Duration.ofSeconds(5));
        EntityDamageEvent fallDamage = new EntityDamageEvent(player, DamageCause.FALL, 15.0);
        listener.onFallDamage(fallDamage);

        assertThat(fallDamage.isCancelled()).isTrue();

        // 12 seconds later (> 10s window): fall damage is no longer shielded
        clock.advance(java.time.Duration.ofSeconds(7));
        EntityDamageEvent fallDamageExpired = new EntityDamageEvent(player, DamageCause.FALL, 15.0);
        listener.onFallDamage(fallDamageExpired);

        assertThat(fallDamageExpired.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("Shields player from PvP attacks within 10-second post-void window")
    void shieldsPvpAfterVoid() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-19T12:00:00Z"), ZoneOffset.UTC);
        VoidProtectionListener listener = new VoidProtectionListener(
                protectionConfig, settingsConfig, loc -> Optional.of(island), clock, Messages.bundled());

        PlayerMock attacker = createPlayer("Attacker");

        // Trigger void recovery
        EntityDamageEvent voidDamage = new EntityDamageEvent(player, DamageCause.VOID, 10.0);
        listener.onVoidDamage(voidDamage);

        // Attacked 3 seconds later
        clock.advance(java.time.Duration.ofSeconds(3));
        EntityDamageByEntityEvent pvpEvent =
                new EntityDamageByEntityEvent(attacker, player, DamageCause.ENTITY_ATTACK, 5.0);
        listener.onPvpDamage(pvpEvent);

        assertThat(pvpEvent.isCancelled()).isTrue();
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(java.time.Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
