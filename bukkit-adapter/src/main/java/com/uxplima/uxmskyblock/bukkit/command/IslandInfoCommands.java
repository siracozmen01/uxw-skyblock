package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is info}: what this island is, in one screen.
 *
 * <p>The design specification publishes it and nothing answered it. Everything it shows was already
 * readable through five separate commands, which is five things to type when a player wants one
 * answer, and the boosters the document asks it to highlight had no listing at all outside the
 * booster command itself.
 */
public final class IslandInfoCommands {

    private final IslandLocationService islandLocationService;
    private final Supplier<@Nullable IslandNameService> nameServiceProvider;
    private final Supplier<@Nullable IslandBoosterService> boosterServiceProvider;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandInfoCommands(
            IslandLocationService islandLocationService,
            Supplier<@Nullable IslandNameService> nameServiceProvider,
            Supplier<@Nullable IslandBoosterService> boosterServiceProvider,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.nameServiceProvider = Objects.requireNonNull(nameServiceProvider, "nameServiceProvider must not be null");
        this.boosterServiceProvider =
                Objects.requireNonNull(boosterServiceProvider, "boosterServiceProvider must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildInfo() {
        return Cmd.literal("info").executes(this::executeInfo);
    }

    private int executeInfo(CommandContext<CommandSourceStack> ctx) {
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

        // Five reads, every one of them a row. They belong on the scheduler, not on the thread
        // Brigadier runs a command on.
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            Optional<Island> optIsland = optIslandId.flatMap(islandLocationService::findIsland);
            if (optIslandId.isEmpty() || optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            report(player, optIslandId.get(), optIsland.get());
        });
        return Cmd.OK;
    }

    private void report(Player player, IslandId islandId, Island island) {
        send(player, "info.header", Placeholder.unparsed("name", nameOf(islandId)));
        send(player, "info.owner", Placeholder.unparsed("owner", ownerNameOf(island)));
        send(
                player,
                "info.members",
                Placeholder.unparsed("count", Integer.toString(island.members().size())));
        send(
                player,
                "info.lifecycle",
                Placeholder.unparsed("lifecycle", lowerCase(island.lifecycle().name())));
        send(player, "info.access", Placeholder.component("access", messages.renderPlain(player, accessKeyOf(island))));

        IslandBoosterService boosters = boosterServiceProvider.get();
        if (boosters == null) {
            return;
        }
        List<IslandBooster> active = boosters.getActiveBoosters(islandId, Instant.now());
        if (active.isEmpty()) {
            send(player, "info.no_boosters");
            return;
        }
        send(player, "info.boosters_header");
        for (IslandBooster booster : active) {
            send(
                    player,
                    "info.booster_entry",
                    Placeholder.unparsed(
                            "category",
                            messages.named(
                                    player,
                                    "booster.categories",
                                    booster.category().name(),
                                    lowerCase(booster.category().key()))),
                    Placeholder.unparsed("multiplier", String.format(Locale.ROOT, "%.2f", booster.multiplier())),
                    Placeholder.unparsed(
                            "remaining",
                            DurationText.coarse(
                                    messages, player, Duration.ofSeconds(Math.max(0L, booster.remainingSeconds())))));
        }
    }

    /**
     * The catalogue key for whether this island takes visitors.
     *
     * <p>A key rather than a sentence, so the word a player reads is theirs to translate, and the
     * three states are three keys rather than three branches inside one line.
     */
    static String accessKeyOf(Island island) {
        Objects.requireNonNull(island, "island must not be null");
        if (island.flags().isEnabled(IslandFlags.LOCKED)) {
            return "info.access_locked";
        }
        if (!island.flags().isEnabled(IslandFlags.VISITOR_ACCESS)) {
            return "info.access_closed";
        }
        return "info.access_open";
    }

    /** The island's own name, or its id when nobody has named it. */
    private String nameOf(IslandId islandId) {
        IslandNameService names = nameServiceProvider.get();
        if (names == null) {
            return islandId.value().toString();
        }
        return names.getIslandName(islandId)
                .map(name -> name.value())
                .orElseGet(() -> islandId.value().toString());
    }

    /** The owner's name, or their uuid when the server has never seen them. */
    private static String ownerNameOf(Island island) {
        String name = Bukkit.getOfflinePlayer(island.ownerPlayerUuid().value()).getName();
        return name == null ? island.ownerPlayerUuid().value().toString() : name;
    }

    private static String lowerCase(String value) {
        return value.toLowerCase(Locale.ROOT);
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
