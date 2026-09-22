package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.bootstrap.SkyblockReloader;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is reload}: read the files an operator may change while the server is running.
 *
 * <p>The specification publishes this and draws the line it holds: the message catalogues and the
 * menu specifications, and nothing else. Core service registrations, Bukkit listeners, connection
 * pools and database tables are never torn down or re-bound. Changing a module is a restart.
 *
 * <p>Reading files is file work, so it runs on the scheduler rather than on the thread Brigadier
 * calls a command on.
 */
public final class IslandReloadCommands {

    private final Supplier<@Nullable SkyblockReloader> reloaderProvider;
    private final SchedulerPort schedulerPort;
    private final Messages messages;

    public IslandReloadCommands(
            Supplier<@Nullable SkyblockReloader> reloaderProvider, SchedulerPort schedulerPort, Messages messages) {
        this.reloaderProvider = Objects.requireNonNull(reloaderProvider, "reloaderProvider must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /**
     * {@code /is reload}.
     *
     * <p>The catalogue publishes a node for exactly this and the gate asked for the general
     * management one instead, so an operator who granted somebody the reload permission and nothing
     * else had granted them nothing.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildReload() {
        return Cmd.literal("reload")
                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_RELOAD.node())
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                .executes(this::executeReload);
    }

    private int executeReload(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        SkyblockReloader reloader = reloaderProvider.get();
        if (reloader == null) {
            send(sender, "reload.unavailable");
            return Cmd.OK;
        }

        send(sender, "reload.starting");
        schedulerPort.async(() -> {
            SkyblockReloader.ReloadReport report = reloader.reload();
            send(
                    sender,
                    "reload.done",
                    Placeholder.unparsed("catalogues", Integer.toString(report.catalogues())),
                    Placeholder.unparsed("menus", Integer.toString(report.menus())));
            for (String failure : report.failures()) {
                send(sender, "reload.failure", Placeholder.unparsed("reason", failure));
            }
        });
        return Cmd.OK;
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        Component line = messages.render(audience, key, resolvers);
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(line);
                }
            });
        } else {
            audience.sendMessage(line);
        }
    }
}
