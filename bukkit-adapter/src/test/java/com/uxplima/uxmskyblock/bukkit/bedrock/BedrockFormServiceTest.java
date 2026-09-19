package com.uxplima.uxmskyblock.bukkit.bedrock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.bedrock.BedrockDetector;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BedrockFormServiceTest {

    private BedrockDetector detector;
    private BedrockScreen screen;
    private BedrockFormService service;
    private Player player;
    private UUID playerUuid;

    @BeforeEach
    void setUp() {
        detector = mock(BedrockDetector.class);
        screen = mock(BedrockScreen.class);
        service = new BedrockFormService(detector, screen);

        player = mock(Player.class);
        playerUuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(playerUuid);
    }

    @Test
    @DisplayName("isBedrock correctly delegates to detector")
    void testIsBedrock() {
        when(detector.isBedrock(playerUuid)).thenReturn(true);
        assertThat(service.isBedrock(player)).isTrue();

        when(detector.isBedrock(playerUuid)).thenReturn(false);
        assertThat(service.isBedrock(player)).isFalse();
    }

    @Test
    @DisplayName("openIslandControlForm passes buttons and triggers callback")
    void testIslandControlForm() {
        Island island = Island.create(
                IslandId.of(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                PlayerUuid.of(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.now());

        AtomicBoolean homeClicked = new AtomicBoolean(false);
        service.openIslandControlForm(player, island, () -> homeClicked.set(true), null, null, null, null);

        ArgumentCaptor<IntConsumer> consumerCaptor = ArgumentCaptor.forClass(IntConsumer.class);
        verify(screen).sendSimpleForm(eq(player), eq("Island Control Panel"), any(), any(), consumerCaptor.capture());

        consumerCaptor.getValue().accept(0);
        assertThat(homeClicked.get()).isTrue();
    }

    @Test
    @DisplayName("openConfirmationModal routes to sendModalForm")
    void testConfirmationModal() {
        AtomicBoolean confirmed = new AtomicBoolean(false);
        service.openConfirmationModal(
                player, "Reset Island?", "Are you sure?", "Yes", "No", () -> confirmed.set(true), () -> {});

        ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(screen)
                .sendModalForm(
                        eq(player),
                        eq("Reset Island?"),
                        eq("Are you sure?"),
                        eq("Yes"),
                        eq("No"),
                        runnableCaptor.capture(),
                        any());

        runnableCaptor.getValue().run();
        assertThat(confirmed.get()).isTrue();
    }

    @Test
    @DisplayName("openIslandSettingsForm parses submitted toggle map into updated IslandFlags")
    void testIslandSettingsForm() {
        IslandFlags original = IslandFlags.defaults();
        assertThat(original.isEnabled(IslandFlags.PVP)).isFalse();

        AtomicReference<IslandFlags> savedFlags = new AtomicReference<>();
        service.openIslandSettingsForm(player, original, savedFlags::set, () -> {});

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Map<String, String>>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(screen).sendCustomForm(eq(player), eq("Island Settings"), any(), any(), consumerCaptor.capture(), any());

        consumerCaptor
                .getValue()
                .accept(Map.of(
                        "PVP", "true",
                        "FIRE_SPREAD", "true"));

        assertThat(savedFlags.get()).isNotNull();
        assertThat(savedFlags.get().isEnabled(IslandFlags.PVP)).isTrue();
        assertThat(savedFlags.get().isEnabled(IslandFlags.FIRE_SPREAD)).isTrue();
    }
}
