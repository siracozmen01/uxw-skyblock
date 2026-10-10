package com.uxplima.uxmskyblock.bukkit.menu;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmskyblock.bukkit.config.PresetChoices;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Every kind of island a server offers is a tile in a window, and a click starts it under the names this server
 * gave the command, unless the kind is kept behind a node the player does not hold.
 */
class TheKindsOfIslandAreAWindowTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final List<String> typed = new ArrayList<>();
    private PlayerMock ada;
    private SkyblockMenuEngine engine;
    private final PresetChoices choices = new PresetChoices(
            Map.of("nether", new PresetChoices.Look("CRIMSON_NYLIUM", "myserver.nether")),
            PresetChoices.WhenNoIsland.MENU,
            true);

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        engine = ShippedTemplates.engineWith(dataDir, "island-create.conf");
    }

    private PresetList list() {
        PresetList list = new PresetList(Messages.bundled(), StarterPresetCatalog::shipped, () -> choices, line -> {
            typed.add(line);
            return "unregistered " + line;
        });
        list.useMenuEngine(engine);
        return list;
    }

    @Test
    @DisplayName("A kind is a tile of its name, what it is, its item, and whether this player may start it")
    void aKindIsATile() {
        List<MenuRow> rows = list().rows(ada);

        assertThat(rows)
                .extracting(row -> row.words().get("id"))
                .containsExactly("classic", "desert", "nether", "cave");
        assertThat(rows.get(0).words())
                .containsEntry("name", "Classic Skyblock")
                .containsEntry("icon", "OAK_SAPLING")
                .containsEntry("access", "Open");
        assertThat(rows.get(2).words()).containsEntry("icon", "CRIMSON_NYLIUM").containsEntry("access", "Locked");
        MenuContext drawn = MenuContext.of(ada, null, 0).withEntry(rows.get(1));
        var renderer = ShippedTemplates.renderer(engine, Messages.bundled());
        var template = ShippedTemplates.template("island-create.conf", "presets");
        assertThat(ShippedTemplates.lore(renderer, template, drawn))
                .contains("Desert Skyblock")
                .contains("An arid island of cacti")
                .contains("For you Open")
                .contains("Click to start this island")
                .doesNotContain("<entry_");
        assertThat(renderer.materialSpec(template, drawn)).isEqualTo("SAND");
    }

    @Test
    @DisplayName("A click on an open kind starts it, and a click on a locked one says so and starts nothing")
    void aClickStartsAnOpenKind() {
        List<MenuRow> rows = list().rows(ada);

        ShippedTemplates.click(
                engine,
                PresetList.CREATE,
                MenuContext.of(ada, null, 0).withEntry(rows.get(1)),
                ada,
                ClickKind.LEFT,
                "");
        ShippedTemplates.click(
                engine,
                PresetList.CREATE,
                MenuContext.of(ada, null, 0).withEntry(rows.get(2)),
                ada,
                ClickKind.LEFT,
                "");

        assertThat(typed).containsExactly("create desert");
        assertThat(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(ada.nextComponentMessage())))
                .contains("Nether Skyblock island is not open to you");
    }

    @Test
    @DisplayName("Showing the kinds opens their file, and answers false with no engine to read it")
    void showingOpensTheFile() {
        PresetList bare = new PresetList(Messages.bundled(), StarterPresetCatalog::shipped, () -> choices, l -> l);
        assertThat(bare.show(ada)).isFalse();

        engine.install();
        assertThat(list().show(ada)).isTrue();
    }
}
