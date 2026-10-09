package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The reset is asked by {@code menus/island-reset.conf}, and the file's two buttons do what the command's
 * own confirmation does.
 *
 * <p>It was asked by a window built in code, so its words were the operator's and its buttons were not.
 */
class TheResetIsAskedFromItsFileTest extends MockBukkitHarness {

    private static final ProfileId PROFILE = new ProfileId(UUID.randomUUID());

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final IslandRecycleService recycle = mock(IslandRecycleService.class);
    private PlayerMock player;
    private IslandResetConfirmationMenu menu;

    @BeforeEach
    void setUp() {
        player = createPlayer("Ada");
        IslandStoragePort storage = mock(IslandStoragePort.class);
        when(storage.findIslandIdByProfileId(PROFILE)).thenReturn(Optional.of(IslandId.of(UUID.randomUUID())));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(PROFILE));
        menu = new IslandResetConfirmationMenu(
                recycle, storage, sessions, new InlineSchedulerPort(), null, Messages.bundled());
    }

    @Test
    @DisplayName("The shipped file names the code the player would type")
    void theFileNamesTheCode() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        var info = java.util.Objects.requireNonNull(
                ShippedTemplates.spec("island-reset.conf").items().get("info"));

        assertThat(ShippedTemplates.lore(
                        ShippedTemplates.renderer(engine, Messages.bundled()),
                        info,
                        MenuContext.of(player, null, 0, Map.of("code", "4321"))))
                .contains("Confirmation code 4321");
    }

    @Test
    @DisplayName("Confirming in the file runs the command's confirmation once, and a second click runs nothing")
    void confirmingRunsTheConfirmationOnce() {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-reset.conf");
        menu.useMenuEngine(engine);
        AtomicInteger confirmed = new AtomicInteger();
        menu.open(player, "4321", confirmed::incrementAndGet);
        MenuContext drawn = MenuContext.of(player, null, 0);

        ShippedTemplates.click(engine, "skyblock:reset-confirm", drawn, player, ClickKind.LEFT, "");
        ShippedTemplates.click(engine, "skyblock:reset-confirm", drawn, player, ClickKind.LEFT, "");

        assertThat(confirmed).hasValue(1);
        verify(recycle, never()).cancelResetChallenge(PROFILE);
    }

    @Test
    @DisplayName("Calling it off in the file stops the code and says so, and confirms nothing")
    void callingItOffStopsTheCode() {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-reset.conf");
        menu.useMenuEngine(engine);
        AtomicInteger confirmed = new AtomicInteger();
        menu.open(player, "4321", confirmed::incrementAndGet);
        while (player.nextComponentMessage() != null) {
            // what opening said, before the click
        }

        ShippedTemplates.click(
                engine, "skyblock:reset-cancel", MenuContext.of(player, null, 0), player, ClickKind.LEFT, "");

        verify(recycle).cancelResetChallenge(PROFILE);
        assertThat(confirmed).hasValue(0);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(player.nextComponentMessage())))
                .contains("Island reset cancelled.");
    }

    @Test
    @DisplayName("Opening the question hands the code to the file, and the window built in code is not drawn")
    void openingUsesTheFile() {
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-reset"), eq(Map.of("code", "4321"))))
                .thenReturn(true);
        menu.useMenuEngine(engine);

        menu.open(player, "4321", () -> {});

        verify(engine).open(player, "island-reset", Map.of("code", "4321"));
        org.bukkit.inventory.@org.jspecify.annotations.Nullable Inventory top =
                player.getOpenInventory().getTopInventory();
        assertThat(top == null || !(top.getHolder() instanceof com.uxplima.uxmlib.gui.Gui))
                .describedAs("no window built in code is open")
                .isTrue();
    }
}
