package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.command.annotation.ConfiguredCommands;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A sentence that names a command names it the way the server registered it.
 *
 * <p>Thirty catalogue lines wrote {@code /is sethome}, {@code /is bank paydebt} and the like into the
 * sentence. After an operator renamed the command, or a word of it, those sentences sent players to
 * a command that no longer answered. A line now writes {@code <cmd:'sethome'>} and reads it under the
 * operator's words.
 */
class ASentenceNamesTheCommandAsRegisteredTest extends MockBukkitHarness {

    @TempDir
    Path dir;

    @Test
    @DisplayName("No catalogue line writes the command out by hand")
    void noLineWritesTheCommandByHand() throws Exception {
        for (String file : List.of("messages_en.conf", "messages_tr.conf")) {
            String text = Files.readString(Path.of("src/main/resources/messages", file));
            assertThat(text).describedAs(file).doesNotContain("/is ").doesNotContain("/island ");
        }
    }

    @Test
    @DisplayName("With the shipped names a sentence reads /is and the shipped word")
    void theShippedNamesRead() {
        Messages messages = Messages.bundled();
        tree(null, messages).buildRoot();

        assertThat(plain(messages, createPlayer("Reader"), "home.list_empty")).contains("/is sethome");
    }

    @Test
    @DisplayName("A renamed command and word read in the sentence, and the rest of the line stays")
    void aRenamedCommandReads() throws Exception {
        Path file = dir.resolve("commands.conf");
        Files.writeString(file, """
                commands { island { name = "ada", aliases = [], subcommands {
                  sethome { name = "evkaydet" }
                  bank { name = "kasa" }
                } } }
                """);
        Messages messages = Messages.bundled();
        tree(ConfiguredCommands.load(file), messages).buildRoot();
        PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));

        assertThat(plain(messages, turkish, "home.list_empty"))
                .contains("/ada evkaydet")
                .doesNotContain("/is ");
        assertThat(plain(messages, turkish, "bank.status_locked_hint")).contains("/ada kasa paydebt");
    }

    private static String plain(Messages messages, PlayerMock player, String key) {
        return PlainTextComponentSerializer.plainText().serialize(messages.renderPlain(player, key));
    }

    private static IslandCommandTree tree(
            @org.jspecify.annotations.Nullable ConfiguredCommands names, Messages messages) {
        IslandProtectionListener listener = mock(IslandProtectionListener.class);
        when(listener.spatialIndex())
                .thenReturn(new com.uxplima.uxmskyblock.bukkit.spatial.SpatialIslandIndex(
                        mock(com.uxplima.uxmskyblock.core.application.island.IslandStoragePort.class), null));
        IslandCommandTree tree = new IslandCommandTree(
                mock(CreateIslandUseCase.class),
                mock(IslandLocationService.class),
                mock(IslandFlagService.class),
                mock(IslandBankService.class),
                mock(IslandUpgradeStoragePort.class),
                mock(IslandLeaderboardService.class),
                mock(BiomeModificationPort.class),
                new StarterPresetCatalog(),
                mock(StarterSchematicEngine.class),
                listener,
                mock(PlayerSessionCoordinator.class),
                new InlineSchedulerPort(),
                messages,
                HomeConfiguration.defaults(),
                ServerNodeId.of("node-1"),
                "world");
        tree.useCommandNames(names);
        return tree;
    }
}
