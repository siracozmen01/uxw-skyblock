package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.worth.IslandScoreBreakdown;
import org.jspecify.annotations.Nullable;

/**
 * Handles island progression and valuation commands:
 * /is level, /is worth, /is value, /is top, /is biome
 */
public final class IslandProgressionCommands {

    private final IslandLocationService islandLocationService;
    private final IslandBankService islandBankService;
    private final IslandLeaderboardService islandLeaderboardService;
    private final BiomeModificationPort biomeModificationPort;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Supplier<@Nullable IslandWorthService> worthServiceProvider;
    private final Supplier<@Nullable IslandMissionService> missionServiceProvider;
    private final Messages messages;

    public IslandProgressionCommands(
            IslandLocationService islandLocationService,
            IslandBankService islandBankService,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Supplier<@Nullable IslandWorthService> worthServiceProvider,
            Supplier<@Nullable IslandMissionService> missionServiceProvider,
            Messages messages) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandLeaderboardService =
                Objects.requireNonNull(islandLeaderboardService, "islandLeaderboardService must not be null");
        this.biomeModificationPort =
                Objects.requireNonNull(biomeModificationPort, "biomeModificationPort must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worthServiceProvider =
                Objects.requireNonNull(worthServiceProvider, "worthServiceProvider must not be null");
        this.missionServiceProvider =
                Objects.requireNonNull(missionServiceProvider, "missionServiceProvider must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLevel() {
        return Cmd.literal("level")
                .executes(this::executeLevel)
                .then(Cmd.literal("recalculate").executes(this::executeLevelRecalculate));
    }

    /**
     * {@code /is recalc}: the short name the extreme scale document publishes for a recalculation.
     *
     * <p>The document has named it since it was written and nothing answered it. A command an
     * operator reads about and types is either there or the document is wrong, and the command is
     * cheaper than the correction.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildRecalc() {
        return Cmd.literal("recalc").executes(this::executeLevelRecalculate);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildWorth() {
        return Cmd.literal("worth").executes(this::executeLevel);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildValue() {
        return Cmd.literal("value").executes(this::executeLevel);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildTop() {
        return Cmd.literal("top")
                .executes(ctx -> executeTop(ctx, "level"))
                .then(Cmd.argument("category", StringArgumentType.word())
                        .executes(ctx -> executeTop(ctx, StringArgumentType.getString(ctx, "category"))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBiome() {
        return Cmd.literal("biome")
                .then(Cmd.argument("type", StringArgumentType.word()).executes(this::executeBiomeChange));
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
    }

    private static TagResolver number(String name, long value) {
        return Placeholder.unparsed(name, String.format(Locale.ROOT, "%,d", value));
    }

    /** Minor units are stored as an integer; a player reads them as money. */
    private static String money(long minorUnits) {
        return String.format(Locale.ROOT, "%,.2f", minorUnits / 100.0);
    }

    private void send(Audience audience, Component component) {
        if (audience instanceof Player player) {
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (player.isOnline()) {
                    player.sendMessage(component);
                }
            });
        } else {
            audience.sendMessage(component);
        }
    }

    private int executeLevel(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            send(player, "level.engine_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, "error.no_island");
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        schedulerPort.async(() -> {
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
            // The finished missions really are counted. This used to pass a hardcoded zero, so
            // levels.quest-weight was a number an operator could set and never see applied: a player
            // who finished every mission scored the same as one who finished none.
            IslandScoreBreakdown score =
                    worthService.calculateScore(islandId, completedMissions(islandId, profileId), bankBalance);
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                send(player, "level.header");
                send(player, "level.calculated", number("level", score.calculatedLevel()));
                send(player, "level.total_score", number("score", score.totalScore()));
                send(player, "level.block_score", number("score", score.blockScore()));
                send(player, "level.spawner_score", number("score", score.spawnerScore()));
                send(player, "level.bank_score", number("score", score.bankScore()));
                send(
                        player,
                        "level.worth",
                        Placeholder.unparsed("worth", money(score.dampedEconomicWorthMinorUnits())));
                send(player, "level.recalculate_hint");
            });
        });
        return Cmd.OK;
    }

    private int executeLevelRecalculate(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            send(player, "level.engine_disabled");
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, "error.no_island");
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        send(player, "level.recalculating");

        schedulerPort.async(() -> {
            Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> optLoc =
                    islandLocationService.findLocation(islandId);
            if (optLoc.isEmpty()) {
                schedulerPort.onEntity(
                        new PlayerUuid(player.getUniqueId()), () -> send(player, "level.details_missing"));
                return;
            }
            com.uxplima.uxmskyblock.core.domain.island.IslandLocation loc = optLoc.get();
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);

            // The same hardcoded zero that once sat in executeLevel sat here too, and this is the
            // worse half: the recalculation is what rescans the island and publishes the new level,
            // so levels.quest-weight was dropped from the number a player is finally given.
            int completed = completedMissions(islandId, profileId);

            worthService.triggerAsyncRecalculation(
                    islandId, loc.worldName(), loc.bounds(), completed, bankBalance, score -> {
                        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                            send(player, "level.recalculated");
                            send(
                                    player,
                                    "level.new_level",
                                    number("level", score.calculatedLevel()),
                                    number("score", score.totalScore()));
                            send(
                                    player,
                                    "level.worth",
                                    Placeholder.unparsed("worth", money(score.dampedEconomicWorthMinorUnits())));
                        });
                    });
        });
        return Cmd.OK;
    }

    private int executeTop(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSourceStack src = ctx.getSource();
        LeaderboardCategory cat =
                switch (category.toLowerCase(Locale.ROOT)) {
                    case "worth" -> LeaderboardCategory.WORTH;
                    case "bank" -> LeaderboardCategory.BANK;
                    default -> LeaderboardCategory.LEVEL;
                };

        schedulerPort.async(() -> {
            var entries = islandLeaderboardService.getTop(cat, 10);
            schedulerPort.onGlobal(() -> {
                Audience audience = src.getSender();
                Component categoryName = messages.renderPlain(
                        audience, "leaderboard.category_" + cat.name().toLowerCase(Locale.ROOT));
                send(audience, "leaderboard.header", Placeholder.component("category", categoryName));
                if (entries.isEmpty()) {
                    send(audience, "leaderboard.empty");
                } else {
                    for (LeaderboardEntry entry : entries) {
                        String name = entry.islandName() != null
                                ? entry.islandName()
                                : entry.islandId().toString().substring(0, 8);
                        send(
                                audience,
                                "leaderboard.entry",
                                Placeholder.unparsed("rank", Integer.toString(entry.rank())),
                                Placeholder.unparsed("name", name),
                                Placeholder.unparsed("score", entry.formattedScore()));
                    }
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBiomeChange(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String biomeName = StringArgumentType.getString(ctx, "type");
        Optional<IslandBiome> optBiome = IslandBiome.fromId(biomeName);
        if (optBiome.isEmpty()) {
            send(player, "biome.unknown", Placeholder.unparsed("name", biomeName));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        IslandBiome targetBiome = optBiome.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> send(player, "error.no_island"));
                return;
            }

            var unused = biomeModificationPort
                    .applyBiome(optIslandId.get(), targetBiome)
                    .thenAccept(success -> {
                        schedulerPort.onEntity(playerUuid, () -> {
                            if (success) {
                                send(player, "biome.changed", Placeholder.unparsed("biome", targetBiome.displayName()));
                            } else {
                                send(player, "biome.failed");
                            }
                        });
                    });
        });

        return Cmd.OK;
    }

    /** How many missions the caller has finished on this island, or none when missions are off. */
    private int completedMissions(IslandId islandId, ProfileId profileId) {
        IslandMissionService missions = missionServiceProvider.get();
        return missions == null ? 0 : missions.countCompleted(islandId, profileId);
    }
}
