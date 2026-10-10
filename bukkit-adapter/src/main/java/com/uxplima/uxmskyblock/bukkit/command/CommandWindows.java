package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import org.jspecify.annotations.Nullable;

/**
 * Opens the window a command is named after.
 *
 * <p>{@code /is upgrade}, {@code /is bank}, {@code /is members}, {@code /is top} and the rest each had a
 * window, reached only through the island menu, and the command printed a list in chat instead. A
 * command named after a window now opens it, drawn as a form for a Bedrock player. The list stays one
 * word further, {@code list}, and a server whose operator removed the window answers in chat as before.
 */
public final class CommandWindows {

    private final Supplier<@Nullable IslandControlMenu> menu;
    private volatile Predicate<Player> roles = player -> false;

    CommandWindows(Supplier<@Nullable IslandControlMenu> menu) {
        this.menu = Objects.requireNonNull(menu, "menu must not be null");
    }

    /** No windows: every command answers in chat. */
    static CommandWindows none() {
        return new CommandWindows(() -> null);
    }

    /** How the roles window is opened. It answers false when the operator removed that window. */
    public void useRoles(Predicate<Player> opensRoles) {
        this.roles = Objects.requireNonNull(opensRoles, "opensRoles must not be null");
    }

    /**
     * Opens {@code specId} for {@code player} and answers true, or answers false when this server has no
     * such window, so the command answers in chat. {@code chat} also runs when the window turns out not
     * to open, for a player without an island to draw it from.
     */
    boolean open(Player player, String specId, Runnable chat) {
        IslandControlMenu control = menu.get();
        if (control == null || !control.hasWindow(specId)) {
            return false;
        }
        control.openWindow(player, specId, chat);
        return true;
    }

    /** Runs a bare command: the window it is named after when there is one, its chat answer otherwise. */
    int openOr(CommandContext<CommandSourceStack> ctx, String specId, Command<CommandSourceStack> chat)
            throws CommandSyntaxException {
        if (ctx.getSource().getSender() instanceof Player player && open(player, specId, () -> answer(chat, ctx))) {
            return Cmd.OK;
        }
        return chat.run(ctx);
    }

    private static void answer(Command<CommandSourceStack> chat, CommandContext<CommandSourceStack> ctx) {
        try {
            chat.run(ctx);
        } catch (CommandSyntaxException e) {
            ctx.getSource()
                    .getSender()
                    .sendMessage(net.kyori.adventure.text.Component.text(String.valueOf(e.getMessage())));
        }
    }

    /** Opens the roles window, or answers false when there is none. */
    boolean openRoles(Player player) {
        return roles.test(player);
    }
}
