package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEvent;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.message.MessagePayload;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is activity}: what has happened on this island lately.
 *
 * <p>The feed, its table and its service have been here since the enterprise foundation work and
 * nothing ever read one. A log nobody can read is a table that only grows.
 */
public final class IslandActivityCommands {

    private static final int DEFAULT_LIMIT = 10;

    private final Supplier<@Nullable ActivityFeedService> activityServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandActivityCommands(
            Supplier<@Nullable ActivityFeedService> activityServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.activityServiceProvider =
                Objects.requireNonNull(activityServiceProvider, "activityServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("activity").executes(this::executeActivity);
    }

    private int executeActivity(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        ActivityFeedService service = activityServiceProvider.get();
        if (service == null) {
            send(player, "activity.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }
            List<ActivityEvent> events =
                    service.getRecentActivities(optIsland.get().value().toString(), DEFAULT_LIMIT);
            Instant now = Instant.now();
            schedulerPort.onEntity(playerUuid, () -> {
                send(player, "activity.header");
                if (events.isEmpty()) {
                    send(player, "activity.empty");
                    return;
                }
                for (ActivityEvent event : events) {
                    sendOne(
                            player,
                            event,
                            DurationText.coarse(messages, player, Duration.between(event.createdAt(), now)));
                }
            });
        });
        return Cmd.OK;
    }

    /**
     * One stored value in this viewer's language.
     *
     * <p>A value stored as {@code @key}, such as a mission's name, is read from the catalogue. A row
     * written before roles, warp categories and presets were stored that way holds the bare id, such
     * as {@code GENERAL} or {@code classic}, and is read the same way by what it names.
     */
    private String readable(Player player, String name, String value) {
        if (value.startsWith("@")) {
            return messages.words(player, value);
        }
        return switch (name) {
            case "role" -> messages.named(player, "roles", value, value);
            case "category" -> messages.named(player, "warp.categories", value, value);
            case "preset" ->
                messages.has("presets." + value + ".name")
                        ? messages.words(player, "@presets." + value + ".name")
                        : value;
            default -> value;
        };
    }

    /**
     * Writes one line of the feed out in the reader's own language.
     *
     * <p>What was stored is the name of a message and the values it has holes for, never the
     * sentence, so a player who reads Turkish reads Turkish and an operator who rewrites a line
     * rewrites the old entries with it.
     *
     * <p>An entry naming a message the catalogue does not have falls back to the plain line, so a
     * row written before a language file was edited still reads.
     */
    private void sendOne(Player player, ActivityEvent event, String ago) {
        java.util.Map<String, String> values = MessagePayload.unpack(event.payloadData());
        String key = event.payloadTypeId();
        if (messages.has(key)) {
            java.util.List<TagResolver> resolvers = new java.util.ArrayList<>(values.size() + 1);
            for (java.util.Map.Entry<String, String> value : values.entrySet()) {
                resolvers.add(Placeholder.unparsed(value.getKey(), readable(player, value.getKey(), value.getValue())));
            }
            resolvers.add(Placeholder.unparsed("ago", ago));
            send(player, key, resolvers.toArray(new TagResolver[0]));
            return;
        }
        send(
                player,
                "activity.entry",
                Placeholder.unparsed("type", event.eventType().name()),
                Placeholder.unparsed("body", values.getOrDefault("body", event.payloadData())),
                Placeholder.unparsed("ago", ago));
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
