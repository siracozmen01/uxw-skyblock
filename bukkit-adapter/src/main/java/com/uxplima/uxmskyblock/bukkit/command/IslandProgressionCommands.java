package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
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

    public IslandProgressionCommands(
            IslandLocationService islandLocationService,
            IslandBankService islandBankService,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Supplier<@Nullable IslandWorthService> worthServiceProvider) {
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
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLevel() {
        return Cmd.literal("level")
                .executes(this::executeLevel)
                .then(Cmd.literal("recalculate").executes(this::executeLevelRecalculate));
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
            send(sender, Component.text("Only in-game players can check island level.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            send(player, Component.text("Island worth and level engine is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        schedulerPort.async(() -> {
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);
            IslandScoreBreakdown score = worthService.calculateScore(islandId, 0, bankBalance);
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                send(
                        player,
                        Component.text("=== Island Level & Valuation ===", NamedTextColor.GOLD, TextDecoration.BOLD));
                send(
                        player,
                        Component.text("Calculated Level: ", NamedTextColor.YELLOW)
                                .append(Component.text(
                                        String.format("%,d", score.calculatedLevel()),
                                        NamedTextColor.GREEN,
                                        TextDecoration.BOLD)));
                send(
                        player,
                        Component.text("Total Score: ", NamedTextColor.YELLOW)
                                .append(Component.text(String.format("%,d", score.totalScore()), NamedTextColor.AQUA)));
                send(
                        player,
                        Component.text(" • Block Score: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        String.format("%,d", score.blockScore()), NamedTextColor.WHITE)));
                send(
                        player,
                        Component.text(" • Spawner Score: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        String.format("%,d", score.spawnerScore()), NamedTextColor.WHITE)));
                send(
                        player,
                        Component.text(" • Bank Score: ", NamedTextColor.GRAY)
                                .append(Component.text(String.format("%,d", score.bankScore()), NamedTextColor.WHITE)));
                send(
                        player,
                        Component.text("Economic Worth: ", NamedTextColor.YELLOW)
                                .append(Component.text(
                                        "$" + String.format("%,.2f", score.dampedEconomicWorthMinorUnits() / 100.0),
                                        NamedTextColor.GOLD)));
                send(
                        player,
                        Component.text(
                                "Use /is level recalculate to rescan all blocks on your island.",
                                NamedTextColor.DARK_GRAY));
            });
        });
        return Cmd.OK;
    }

    private int executeLevelRecalculate(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can recalculate island level.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandWorthService worthService = worthServiceProvider.get();
        if (worthService == null) {
            send(player, Component.text("Island worth and level engine is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        send(
                player,
                Component.text(
                        "Recalculating island blocks and valuation across region chunks...", NamedTextColor.YELLOW));

        schedulerPort.async(() -> {
            Optional<com.uxplima.uxmskyblock.core.domain.island.IslandLocation> optLoc =
                    islandLocationService.findLocation(islandId);
            if (optLoc.isEmpty()) {
                schedulerPort.onEntity(
                        new PlayerUuid(player.getUniqueId()),
                        () -> send(player, Component.text("Could not find island details.", NamedTextColor.RED)));
                return;
            }
            com.uxplima.uxmskyblock.core.domain.island.IslandLocation loc = optLoc.get();
            long bankBalance = islandBankService.getBalanceMinorUnits(profileId).orElse(0L);

            worthService.triggerAsyncRecalculation(islandId, loc.worldName(), loc.bounds(), 0, bankBalance, score -> {
                schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                    send(
                            player,
                            Component.text(
                                    "Island recalculation complete!", NamedTextColor.GREEN, TextDecoration.BOLD));
                    send(
                            player,
                            Component.text("New Level: ", NamedTextColor.YELLOW)
                                    .append(Component.text(
                                            String.format("%,d", score.calculatedLevel()),
                                            NamedTextColor.GREEN,
                                            TextDecoration.BOLD))
                                    .append(Component.text(
                                            " (Total Score: " + String.format("%,d", score.totalScore()) + ")",
                                            NamedTextColor.GRAY)));
                    send(
                            player,
                            Component.text("Economic Worth: ", NamedTextColor.YELLOW)
                                    .append(Component.text(
                                            "$" + String.format("%,.2f", score.dampedEconomicWorthMinorUnits() / 100.0),
                                            NamedTextColor.GOLD)));
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
                send(src.getSender(), Component.text("--- Top Islands (" + cat.name() + ") ---", NamedTextColor.GOLD));
                if (entries.isEmpty()) {
                    send(src.getSender(), Component.text("No islands ranked yet.", NamedTextColor.GRAY));
                } else {
                    for (LeaderboardEntry entry : entries) {
                        String name = entry.islandName() != null
                                ? entry.islandName()
                                : entry.islandId().toString().substring(0, 8);
                        send(
                                src.getSender(),
                                Component.text(
                                        "#" + entry.rank() + " " + name + " - " + entry.formattedScore(),
                                        NamedTextColor.YELLOW));
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
            send(player, Component.text("Unknown biome '" + biomeName + "'.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        IslandBiome targetBiome = optBiome.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(player, Component.text("You do not have an island.", NamedTextColor.RED)));
                return;
            }

            var unused = biomeModificationPort
                    .applyBiome(optIslandId.get(), targetBiome)
                    .thenAccept(success -> {
                        schedulerPort.onEntity(playerUuid, () -> {
                            if (success) {
                                send(
                                        player,
                                        Component.text(
                                                "Island biome changed to " + targetBiome.displayName() + "!",
                                                NamedTextColor.GREEN));
                            } else {
                                send(player, Component.text("Failed to update island biome.", NamedTextColor.RED));
                            }
                        });
                    });
        });

        return Cmd.OK;
    }
}
