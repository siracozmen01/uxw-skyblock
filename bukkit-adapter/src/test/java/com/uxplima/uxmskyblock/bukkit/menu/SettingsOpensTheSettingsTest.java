package com.uxplima.uxmskyblock.bukkit.menu;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.CommandDispatcher;
import com.uxplima.uxmskyblock.bukkit.command.IslandFeatures;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /is settings} opens the island's settings, not the island menu they sit under. */
class SettingsOpensTheSettingsTest extends MockBukkitHarness {

    @Test
    @DisplayName("/is settings opens the settings window, and the island menu where the operator removed it")
    void settingsOpensTheSettings() throws Exception {
        IslandControlMenu control = mock(IslandControlMenu.class);
        when(control.hasWindow("island-settings")).thenReturn(true);
        CommandDispatcher<CommandSourceStack> dispatcher = EveryMenuCommandParsesTest.dispatcher(
                IslandFeatures.builder().controlMenu(control).build());
        PlayerMock ada = createPlayer("Ada");
        CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(ada);

        dispatcher.execute("island settings", source);

        verify(control).openWindow(eq(ada), eq("island-settings"), any(Runnable.class));
        verify(control, never()).open(any(Player.class));

        when(control.hasWindow(anyString())).thenReturn(false);
        dispatcher.execute("island settings", source);

        verify(control).open(ada);
    }
}
