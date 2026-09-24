package com.uxplima.uxmskyblock.bukkit.command;

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

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.season.IslandSeasonService;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.season.SeasonId;
import com.uxplima.uxmskyblock.core.domain.season.SeasonMetric;
import com.uxplima.uxmskyblock.core.domain.season.SeasonRecord;
import com.uxplima.uxmskyblock.core.domain.season.SeasonSnapshotEntry;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is season}: which season is running, how it went, and what it owed you.
 *
 * <p>The season service, its snapshots, its payouts and its storage have been here since the
 * season work and no command reached any of it. The design specification publishes
 * {@code /is season top <season_number>} and a player could not ask which season they were in.
 */
public final class IslandSeasonCommands {

    private static final int PAGE_SIZE = 10;

    private final Supplier<@Nullable IslandSeasonService> seasonServiceProvider;
    private final SchedulerPort schedulerPort;
    private final Messages messages;

    public IslandSeasonCommands(
            Supplier<@Nullable IslandSeasonService> seasonServiceProvider,
            SchedulerPort schedulerPort,
            Messages messages) {
        this.seasonServiceProvider =
                Objects.requireNonNull(seasonServiceProvider, "seasonServiceProvider must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildSeason() {
        return Cmd.literal("season")
                .executes(this::executeCurrent)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.literal("rewards").executes(this::executeRewards))
                .then(Cmd.literal("top")
                        .executes(ctx -> executeTop(ctx, null, SeasonMetric.LEVEL))
                        .then(Cmd.argument("season", IntegerArgumentType.integer(1))
                                .executes(ctx -> executeTop(
                                        ctx, IntegerArgumentType.getInteger(ctx, "season"), SeasonMetric.LEVEL))
                                .then(Cmd.argument("metric", StringArgumentType.word())
                                        .executes(this::executeTopWithMetric))));
    }

    private int executeCurrent(CommandContext<CommandSourceStack> ctx) {
        return withService(ctx, (sender, service) -> {
            Optional<SeasonRecord> active = service.activeSeason();
            if (active.isEmpty()) {
                send(sender, "season.none_running");
                return;
            }
            SeasonRecord season = active.get();
            send(
                    sender,
                    "season.current",
                    Placeholder.unparsed("number", Integer.toString(season.id().number())),
                    Placeholder.unparsed("name", season.name()));
        });
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        return withService(ctx, (sender, service) -> {
            List<SeasonRecord> seasons = service.seasons();
            send(sender, "season.list_header", Placeholder.unparsed("count", Integer.toString(seasons.size())));
            if (seasons.isEmpty()) {
                send(sender, "season.list_empty");
                return;
            }
            for (SeasonRecord season : seasons) {
                send(
                        sender,
                        "season.list_entry",
                        Placeholder.unparsed(
                                "number", Integer.toString(season.id().number())),
                        Placeholder.unparsed("name", season.name()),
                        Placeholder.unparsed(
                                "state",
                                messages.named(
                                        sender,
                                        "season.states",
                                        season.state().name(),
                                        season.state().name().toLowerCase(Locale.ROOT))));
            }
        });
    }

    private int executeTopWithMetric(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "metric");
        for (SeasonMetric metric : SeasonMetric.values()) {
            if (metric.name().equalsIgnoreCase(raw)) {
                return executeTop(ctx, IntegerArgumentType.getInteger(ctx, "season"), metric);
            }
        }
        send(
                ctx.getSource().getSender(),
                "season.unknown_metric",
                Placeholder.unparsed("metric", raw),
                Placeholder.unparsed("metrics", metricNames()));
        return Cmd.OK;
    }

    /**
     * The standings of one season, or of the one running when the caller named none.
     *
     * <p>A season nobody named is the active one, because a player who types {@code /is season top}
     * during a season means this season.
     */
    private int executeTop(CommandContext<CommandSourceStack> ctx, @Nullable Integer number, SeasonMetric metric) {
        return withService(ctx, (sender, service) -> {
            Optional<SeasonRecord> optSeason =
                    number == null ? service.activeSeason() : service.season(SeasonId.of(number));
            if (optSeason.isEmpty()) {
                send(
                        sender,
                        number == null ? "season.none_running" : "season.unknown_season",
                        Placeholder.unparsed("number", number == null ? "" : Integer.toString(number)));
                return;
            }

            SeasonRecord season = optSeason.get();
            List<SeasonSnapshotEntry> standings = service.standings(season.id(), metric, PAGE_SIZE);
            send(
                    sender,
                    "season.top_header",
                    Placeholder.unparsed("number", Integer.toString(season.id().number())),
                    Placeholder.unparsed("metric", metric.name().toLowerCase(Locale.ROOT)));
            if (standings.isEmpty()) {
                send(sender, "season.top_empty");
                return;
            }
            for (SeasonSnapshotEntry entry : standings) {
                send(
                        sender,
                        "season.top_entry",
                        Placeholder.unparsed("rank", Integer.toString(entry.rank())),
                        Placeholder.unparsed("player", nameOf(entry.ownerUuid())),
                        Placeholder.unparsed("score", String.format(Locale.ROOT, "%,d", entry.score())));
            }
        });
    }

    /**
     * Hands over whatever last season owed the caller.
     *
     * <p>The payouts were queued by the season rollover and nothing could collect one, so a player
     * who won a season was owed a reward that stayed owed.
     */
    private int executeRewards(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandSeasonService service = seasonServiceProvider.get();
        if (service == null) {
            send(player, "season.disabled");
            return Cmd.OK;
        }

        schedulerPort.async(() -> {
            PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
            int dispatched = service.dispatchPendingPayouts(
                    playerUuid,
                    action -> schedulerPort.onGlobal(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), action)));
            send(
                    player,
                    dispatched > 0 ? "season.rewards_claimed" : "season.rewards_none",
                    Placeholder.unparsed("count", Integer.toString(dispatched)));
        });
        return Cmd.OK;
    }

    private static String metricNames() {
        StringBuilder out = new StringBuilder();
        for (SeasonMetric metric : SeasonMetric.values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(metric.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String nameOf(PlayerUuid playerUuid) {
        String name = Bukkit.getOfflinePlayer(playerUuid.value()).getName();
        return name == null ? playerUuid.value().toString() : name;
    }

    /** What a season command needs: somebody to answer, and the service, off the command thread. */
    @FunctionalInterface
    private interface SeasonAction {
        void run(Audience sender, IslandSeasonService service);
    }

    private int withService(CommandContext<CommandSourceStack> ctx, SeasonAction action) {
        Audience sender = ctx.getSource().getSender();
        IslandSeasonService service = seasonServiceProvider.get();
        if (service == null) {
            send(sender, "season.disabled");
            return Cmd.OK;
        }
        // Reading a season, its standings or its payouts is a query every time.
        schedulerPort.async(() -> action.run(sender, service));
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
