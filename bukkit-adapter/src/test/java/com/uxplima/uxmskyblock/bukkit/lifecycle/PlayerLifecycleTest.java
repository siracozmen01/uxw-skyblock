package com.uxplima.uxmskyblock.bukkit.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleOwedEffectsPort;
import com.uxplima.uxmskyblock.core.application.lifecycle.LifecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecyclePolicy;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleRule;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A leave, a kick, a death and a reset do to a player what the operator's rules say, now if the player
 * is here and when they next play if not.
 */
class PlayerLifecycleTest extends MockBukkitHarness {

    private final Map<PlayerUuid, Set<LifecycleEffect>> owed = new ConcurrentHashMap<>();
    private final Map<ProfileId, GameModeType> modes = new ConcurrentHashMap<>();
    private final Map<ProfileId, ProfileType> rulesets = new ConcurrentHashMap<>();
    private final List<ProfileId> learned = new ArrayList<>();

    @SuppressWarnings("NullAway.Init")
    private PlayerLifecycle lifecycle;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock player;

    @SuppressWarnings("NullAway.Init")
    private ProfileId profile;

    @BeforeEach
    void setUpLifecycle() {
        server.addSimpleWorld("world");
        player = createPlayer("Wanderer");
        profile = new ProfileId(UUID.randomUUID());
        LifecyclePolicy policy = new LifecyclePolicy(List.of(
                new LifecycleRule(
                        LifecycleEvent.KICK,
                        null,
                        null,
                        Map.of(LifecycleEffect.CLEAR_INVENTORY, true, LifecycleEffect.CLEAR_ENDER_CHEST, true)),
                new LifecycleRule(
                        LifecycleEvent.DEATH,
                        GameModeType.ONEBLOCK,
                        null,
                        Map.of(LifecycleEffect.KEEP_INVENTORY, true, LifecycleEffect.KEEP_EXPERIENCE, true)),
                new LifecycleRule(
                        LifecycleEvent.DEATH,
                        null,
                        ProfileType.HARDCORE,
                        Map.of(LifecycleEffect.KEEP_INVENTORY, false, LifecycleEffect.CLEAR_ENDER_CHEST, true))));
        LifecycleService service = new LifecycleService(
                policy,
                memory(),
                id -> {
                    learned.add(id);
                    return modes.getOrDefault(id, GameModeType.SKYBLOCK);
                },
                id -> Optional.ofNullable(modes.get(id)),
                id -> rulesets.getOrDefault(id, ProfileType.CLASSIC),
                id -> Optional.ofNullable(rulesets.get(id)));
        lifecycle = new PlayerLifecycle(service, inline(), uuid -> Optional.of(profile));
    }

    @Test
    @DisplayName("A kick empties the inventory and ender chest of a player who is here, and nothing stays owed")
    void aKickIsPaidAtOnce() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        player.getEnderChest().addItem(new ItemStack(Material.EMERALD, 3));

        lifecycle.happened(LifecycleEvent.KICK, new PlayerUuid(player.getUniqueId()), profile);

        assertThat(player.getInventory().contains(Material.DIAMOND)).isFalse();
        assertThat(player.getEnderChest().contains(Material.EMERALD)).isFalse();
        assertThat(owed.getOrDefault(new PlayerUuid(player.getUniqueId()), Set.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("A kick of a player who is away is owed, and paid when their next session is made")
    void aKickOfAnAbsentPlayerIsOwed() {
        PlayerUuid away = new PlayerUuid(UUID.randomUUID());
        lifecycle.happened(LifecycleEvent.KICK, away, profile);
        assertThat(owed.get(away))
                .containsExactlyInAnyOrder(LifecycleEffect.CLEAR_INVENTORY, LifecycleEffect.CLEAR_ENDER_CHEST);

        owed.put(new PlayerUuid(player.getUniqueId()), EnumSet.of(LifecycleEffect.CLEAR_INVENTORY));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));
        lifecycle.onSessionActive(player);

        assertThat(player.getInventory().contains(Material.DIAMOND)).isFalse();
        assertThat(owed.get(new PlayerUuid(player.getUniqueId()))).isEmpty();
        assertThat(learned)
                .describedAs("the session reads the mode ahead of a death")
                .contains(profile);
    }

    @Test
    @DisplayName("A leave nobody wrote a rule for does nothing and owes nothing")
    void aLeaveWithoutARuleDoesNothing() {
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        lifecycle.happened(LifecycleEvent.LEAVE, new PlayerUuid(player.getUniqueId()), profile);

        assertThat(player.getInventory().contains(Material.DIAMOND)).isTrue();
        assertThat(owed.getOrDefault(new PlayerUuid(player.getUniqueId()), Set.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("A death on a OneBlock island keeps what was carried and the experience")
    void aOneBlockDeathKeeps() {
        modes.put(profile, GameModeType.ONEBLOCK);
        PlayerDeathEvent death = death();

        lifecycle.onDeath(death);

        verify(death).setKeepInventory(true);
        verify(death).setKeepLevel(true);
        verify(death).setDroppedExp(0);
    }

    @Test
    @DisplayName("A Hardcore death keeps nothing even on a OneBlock island, and loses the ender chest")
    void aHardcoreDeathLoses() {
        modes.put(profile, GameModeType.ONEBLOCK);
        rulesets.put(profile, ProfileType.HARDCORE);
        player.getEnderChest().addItem(new ItemStack(Material.EMERALD, 3));
        PlayerDeathEvent death = death();

        lifecycle.onDeath(death);

        verify(death, never()).setKeepInventory(true);
        verify(death).setKeepLevel(true);
        assertThat(player.getEnderChest().contains(Material.EMERALD)).isFalse();
    }

    @Test
    @DisplayName("A death before the mode was read is a skyblock death, which no rule here changes")
    void aDeathBeforeTheModeIsKnown() {
        PlayerDeathEvent death = death();

        lifecycle.onDeath(death);

        verify(death, never()).setKeepInventory(true);
        verify(death, never()).setKeepLevel(true);
    }

    private PlayerDeathEvent death() {
        PlayerDeathEvent death = mock(PlayerDeathEvent.class);
        when(death.getEntity()).thenReturn(player);
        when(death.getDrops()).thenReturn(new ArrayList<>());
        return death;
    }

    private static SchedulerPort inline() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        return scheduler;
    }

    private LifecycleOwedEffectsPort memory() {
        return new LifecycleOwedEffectsPort() {
            @Override
            public void owe(PlayerUuid playerUuid, Set<LifecycleEffect> effects) {
                if (!effects.isEmpty()) {
                    owed.computeIfAbsent(playerUuid, id -> EnumSet.noneOf(LifecycleEffect.class))
                            .addAll(effects);
                }
            }

            @Override
            public Set<LifecycleEffect> owed(PlayerUuid playerUuid) {
                return Set.copyOf(owed.getOrDefault(playerUuid, Set.of()));
            }

            @Override
            public void settle(PlayerUuid playerUuid, Set<LifecycleEffect> paid) {
                owed.computeIfPresent(playerUuid, (id, held) -> {
                    held.removeAll(paid);
                    return held;
                });
            }
        };
    }
}
