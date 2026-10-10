package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmlib.menu.spec.Ref;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The boosters are {@code menus/island-booster-cards.conf}: one card for each kind, drawn from what the
 * window read off the player's thread.
 */
class TheBoostersAreDrawnFromTheirFileTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final IslandBoosterService.BoosterOverview QUIET =
            new IslandBoosterService.BoosterOverview(false, List.of(), Map.of());

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final IslandStoragePort storage = mock(IslandStoragePort.class);
    private final IslandBoosterService boosters = mock(IslandBoosterService.class);
    private final PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
    private PlayerMock player;
    private IslandBoosterMenu menu;

    @BeforeEach
    void setUp() {
        player = createPlayer("Okur");
        player.setLocale(java.util.Locale.forLanguageTag("tr"));
        menu = new IslandBoosterMenu(
                storage, boosters, BoosterConfiguration.defaultConfiguration(), sessions, Messages.bundled());
    }

    @Test
    @DisplayName("Every kind of booster is a card, in order, its state and its bar named by the catalogue")
    void everyKindIsACard() {
        List<MenuRow> rows = menu.rows(player, QUIET, NOW);

        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).words())
                .containsEntry("material", "SPAWNER")
                .containsEntry("status", "<key:menu.booster.status_inactive>")
                .containsEntry("bar", "<key:menu.booster.bar_empty>".repeat(10));
    }

    @Test
    @DisplayName("A card reads in the reader's words and colours: no key, no tag, the bar drawn")
    void aCardReadsInWords() {
        SkyblockMenuEngine engine = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());
        MenuContext drawn = MenuContext.of(player, null, 0)
                .withEntry(menu.rows(player, QUIET, NOW).get(0));

        assertThat(ShippedTemplates.lore(
                        renderer, ShippedTemplates.template("island-booster-cards.conf", "cards"), drawn))
                .contains("Durum Etkin değil")
                .contains("□□□□□□□□□□")
                .doesNotContain("<key:")
                .doesNotContain("<entry_");
        var overview = java.util.Objects.requireNonNull(
                ShippedTemplates.spec("island-booster-cards.conf").items().get("overview"));
        assertThat(ShippedTemplates.lore(
                        renderer, overview, MenuContext.of(player, null, 0, menu.overviewValues(QUIET))))
                .contains("Etkin güçlendirici 0")
                .contains("Çalışıyor");
    }

    @Test
    @DisplayName("The boosters tile of the island menu opens the boosters, with no window of tiles in between")
    void theIslandMenuOpensTheBoosters() {
        var tile = java.util.Objects.requireNonNull(
                ShippedTemplates.spec("island-main.conf").items().get("boosters"));
        var back = java.util.Objects.requireNonNull(
                ShippedTemplates.spec("island-booster-cards.conf").items().get("back"));

        assertThat(tile.click().actionsFor(ClickKind.LEFT)).extracting(Ref::id).contains("skyblock:island:booster");
        assertThat(back.click().actionsFor(ClickKind.LEFT)).contains(Ref.parse("open:island-main"));
        assertThat(Path.of("src/main/resources/menus/island-boosters.conf")).doesNotExist();
    }

    @Test
    @DisplayName("An inactive booster reads in the colour of a bad state, not in the colour of the words around it")
    void anInactiveBoosterReadsAsBad() {
        Messages messages = Messages.bundled();
        net.kyori.adventure.text.format.TextColor bad =
                colourOf(messages.provider().renderTemplate(messages.paint(player, "<bad>x")));
        net.kyori.adventure.text.format.TextColor muted =
                colourOf(messages.provider().renderTemplate(messages.paint(player, "<muted>x")));
        assertThat(bad).isNotNull().isNotEqualTo(muted);

        for (String language : List.of("en", "tr")) {
            player.setLocale(java.util.Locale.forLanguageTag(language));
            assertThat(colourOf(messages.renderPlain(player, "menu.booster.status_inactive")))
                    .describedAs("inactive in %s", language)
                    .isEqualTo(bad);
        }
    }

    /** The first colour a line is drawn in, reading its parts in order. */
    private static net.kyori.adventure.text.format.@org.jspecify.annotations.Nullable TextColor colourOf(
            net.kyori.adventure.text.Component line) {
        if (line.color() != null) {
            return line.color();
        }
        for (net.kyori.adventure.text.Component child : line.children()) {
            net.kyori.adventure.text.format.TextColor colour = colourOf(child);
            if (colour != null) {
                return colour;
            }
        }
        return null;
    }

    @Test
    @DisplayName("Opening the boosters hands the cards to the file, and the window built in code is not drawn")
    void openingUsesTheFile() {
        ProfileId profile = new ProfileId(player.getUniqueId());
        IslandId island = IslandId.of(UUID.randomUUID());
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profile));
        when(storage.findIslandIdByProfileId(profile)).thenReturn(Optional.of(island));
        when(boosters.overview(eq(island), any(Instant.class))).thenReturn(QUIET);
        SkyblockMenuEngine engine = mock(SkyblockMenuEngine.class);
        when(engine.open(eq(player), eq("island-booster-cards"), anyMap(), anyMap()))
                .thenReturn(true);
        menu.useMenuEngine(engine);

        menu.open(player);

        verify(engine).open(eq(player), eq("island-booster-cards"), eq(menu.overviewValues(QUIET)), anyMap());
        org.bukkit.inventory.@org.jspecify.annotations.Nullable Inventory top =
                player.getOpenInventory().getTopInventory();
        assertThat(top == null || !(top.getHolder() instanceof com.uxplima.uxmlib.gui.Gui))
                .describedAs("no window built in code is open")
                .isTrue();
    }
}
