package com.uxplima.uxmskyblock.bukkit.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.uxplima.uxmskyblock.bukkit.config.HomeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.integration.economy.SkyblockEconomyBridge;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A command named after a window opens it, and its list is one word further.
 *
 * <p>{@code /is upgrade}, {@code /is bank}, {@code /is members}, {@code /is top}, {@code /is biome} and
 * {@code /is homes} each had a window, reached only through the island menu, and the command printed a
 * list in chat. A tester asked why {@code /is upgrade} opened no menu. The window opens now, drawn as a
 * form for a Bedrock player, and a server whose operator removed the window still answers in chat.
 */
class ACommandNamedAfterAWindowOpensItTest extends MockBukkitHarness {

    private final IslandControlMenu control = mock(IslandControlMenu.class);
    private final Set<String> kept = new java.util.HashSet<>();
    private final List<String> opened = new ArrayList<>();
    private final List<String> rolesOpened = new ArrayList<>();
    private final CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
    private PlayerMock ada;

    @BeforeEach
    void setUp() {
        ada = createPlayer("Ada");
        when(control.hasWindow(anyString())).thenAnswer(call -> kept.contains(call.getArgument(0, String.class)));
        doAnswer(call -> {
                    opened.add(call.getArgument(1, String.class));
                    return null;
                })
                .when(control)
                .openWindow(any(Player.class), anyString(), any(Runnable.class));
        CommandWindows windows = new CommandWindows(() -> control);
        windows.useRoles(player -> kept.contains("island-roles") && rolesOpened.add(player.getName()));

        IslandLocationService locations = mock(IslandLocationService.class);
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(any(java.util.UUID.class))).thenReturn(Optional.empty());
        InlineSchedulerPort scheduler = new InlineSchedulerPort();
        Messages messages = Messages.bundled();

        IslandUpgradeCommands upgrades = new IslandUpgradeCommands(
                () -> null, locations, scheduler, ServerNodeId.of("node-1"), messages, sessions);
        IslandBankCommands bank = new IslandBankCommands(
                mock(IslandBankService.class),
                locations,
                mock(SkyblockEconomyBridge.class),
                scheduler,
                ServerNodeId.of("node-1"),
                () -> null,
                messages,
                sessions);
        IslandProgressionCommands progression = new IslandProgressionCommands(
                locations,
                mock(IslandBankService.class),
                mock(IslandLeaderboardService.class),
                mock(com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort.class),
                sessions,
                scheduler,
                () -> null,
                () -> null,
                messages);
        IslandMembershipCommands membership =
                new IslandMembershipCommands(() -> null, locations, scheduler, messages, sessions);
        IslandHomeCommands homes = new IslandHomeCommands(
                () -> null, locations, scheduler, HomeConfiguration.defaults(), messages, sessions);
        for (Consumer<CommandWindows> each : List.<Consumer<CommandWindows>>of(
                upgrades::useWindows,
                bank::useWindows,
                progression::useWindows,
                membership::useWindows,
                homes::useWindows)) {
            each.accept(windows);
        }
        for (LiteralArgumentBuilder<CommandSourceStack> branch : List.of(
                upgrades.buildUnder("upgrade"),
                bank.build(),
                progression.buildTop(),
                progression.buildBiome(),
                membership.buildMembers(),
                membership.buildPermissions(),
                homes.buildNamedHome())) {
            dispatcher.register(branch);
        }
    }

    @ParameterizedTest(name = "/is {0} opens {1}")
    @CsvSource({
        "upgrade, island-upgrades",
        "bank, island-bank",
        "top, island-top",
        "biome, island-biome",
        "members, island-members",
        "homes, island-homes"
    })
    @DisplayName("The bare command opens its window and says nothing in chat")
    void theBareCommandOpensTheWindow(String command, String window) throws Exception {
        kept.add(window);

        run(command);

        assertThat(opened).containsExactly(window);
        assertThat(ada.nextComponentMessage()).describedAs("nothing in chat").isNull();
    }

    @ParameterizedTest(name = "/is {0} answers in chat without {1}")
    @CsvSource({
        "upgrade, island-upgrades",
        "bank, island-bank",
        "top, island-top",
        "biome, island-biome",
        "members, island-members",
        "homes, island-homes"
    })
    @DisplayName("Where the operator removed the window, the bare command answers in chat as before")
    void withoutTheWindowTheCommandAnswersInChat(String command, String window) throws Exception {
        run(command);

        assertThat(opened).isEmpty();
        verify(control, never()).openWindow(any(Player.class), eq(window), any(Runnable.class));
        assertThat(ada.nextComponentMessage()).describedAs("an answer in chat").isNotNull();
    }

    @ParameterizedTest(name = "/is {0} list")
    @CsvSource({"upgrade", "biome", "members", "homes", "permissions"})
    @DisplayName("The list word answers in chat even where the window is kept")
    void theListWordAnswersInChat(String command) throws Exception {
        kept.addAll(List.of("island-upgrades", "island-biome", "island-members", "island-homes", "island-roles"));

        run(command + " list");

        assertThat(opened).isEmpty();
        assertThat(rolesOpened).isEmpty();
        assertThat(ada.nextComponentMessage()).describedAs("an answer in chat").isNotNull();
    }

    @ParameterizedTest(name = "roles window kept: {0}")
    @CsvSource({"true", "false"})
    @DisplayName("/is permissions opens the roles window, or lists the roles in chat without it")
    void permissionsOpensTheRoles(boolean keep) throws Exception {
        if (keep) {
            kept.add("island-roles");
        }

        run("permissions");

        assertThat(rolesOpened).hasSize(keep ? 1 : 0);
        assertThat(ada.nextComponentMessage() != null).isEqualTo(!keep);
    }

    @ParameterizedTest(name = "/is {0}")
    @CsvSource({"upgrade, island-upgrades", "members, island-members"})
    @DisplayName("A window that cannot be drawn after all, for want of an island, answers in chat instead")
    void aWindowThatCannotOpenAnswersInChat(String command, String window) throws Exception {
        kept.add(window);
        doAnswer(call -> {
                    call.getArgument(2, Runnable.class).run();
                    return null;
                })
                .when(control)
                .openWindow(any(Player.class), anyString(), any(Runnable.class));

        run(command);

        assertThat(ada.nextComponentMessage()).describedAs("an answer in chat").isNotNull();
    }

    private void run(String line) throws Exception {
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(ada);
        dispatcher.execute(line, source);
    }
}
