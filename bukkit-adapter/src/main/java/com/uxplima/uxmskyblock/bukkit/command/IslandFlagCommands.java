package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.flag.IslandFlagService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is flag}: the switches that decide what may happen on an island.
 *
 * <p>Sixteen flags are defined, the protection listener reads every one of them on every event, and
 * no command could change a single one. PvP was on or off according to a default nobody could move.
 * The Bedrock settings form could submit a change and there was no Java path at all.
 */
public final class IslandFlagCommands {

    private final IslandFlagService flagService;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandFlagCommands(
            IslandFlagService flagService,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.flagService = Objects.requireNonNull(flagService, "flagService must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("flag")
                .executes(this::executeList)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeToggle));
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        return onOwnIsland(ctx, (player, island, profileId) -> {
            Map<String, Boolean> flags = flagService.flagsOf(island);
            onEntity(player, () -> {
                send(player, "flag.header");
                flags.forEach((name, enabled) -> send(
                        player,
                        enabled ? "flag.entry_on" : "flag.entry_off",
                        Placeholder.unparsed("flag", nameOf(player, name)),
                        Placeholder.unparsed("key", name.toLowerCase(Locale.ROOT))));
                send(player, "flag.toggle_hint");
            });
        });
    }

    private int executeToggle(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "name");
        return onOwnIsland(ctx, (player, island, profileId) -> {
            IslandFlagService.FlagChange change = flagService.toggle(island, profileId, raw);
            onEntity(player, () -> announce(player, change));
        });
    }

    private void announce(Player player, IslandFlagService.FlagChange change) {
        switch (change) {
            case IslandFlagService.FlagChange.Changed changed ->
                send(
                        player,
                        changed.enabled() ? "flag.turned_on" : "flag.turned_off",
                        Placeholder.unparsed("flag", nameOf(player, changed.flag())));
            case IslandFlagService.FlagChange.UnknownFlag unknown ->
                send(
                        player,
                        "flag.unknown",
                        Placeholder.unparsed("flag", unknown.flag()),
                        Placeholder.unparsed("flags", unknown.available()));
            case IslandFlagService.FlagChange.NotAllowed ignored -> send(player, "flag.no_permission");
            case IslandFlagService.FlagChange.IslandMissing ignored -> send(player, "error.no_island");
        }
    }

    /**
     * A flag as the reader's language names it. The list and the toggle said {@code pvp} and
     * {@code monster_spawn}, the keys the code keeps; the key is still what a player types, so the
     * list shows it beside the name.
     */
    private String nameOf(Player player, String flag) {
        return messages.named(player, "flag.names", flag, flag.toLowerCase(Locale.ROOT));
    }

    @FunctionalInterface
    private interface IslandAction {
        void run(Player player, Island island, ProfileId profileId);
    }

    private int onOwnIsland(CommandContext<CommandSourceStack> ctx, IslandAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                onEntity(player, () -> send(player, "error.no_island"));
                return;
            }
            Optional<Island> optIsland = islandLocationService.findIsland(optIslandId.get());
            if (optIsland.isEmpty()) {
                onEntity(player, () -> send(player, "error.no_island"));
                return;
            }
            action.run(player, optIsland.get(), profileId);
        });
        return Cmd.OK;
    }

    private void onEntity(Player player, Runnable work) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                work.run();
            }
        });
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
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
