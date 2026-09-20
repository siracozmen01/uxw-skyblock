package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterApplyResult;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.limit.LimitCategory;
import com.uxplima.uxmskyblock.core.domain.limit.LimitType;
import org.jspecify.annotations.Nullable;

/**
 * Handles island mechanics commands:
 * /is limits, /is quarantine, /is booster, /is missions, /is challenges, /is border, /is bounds
 */
public final class IslandMechanicsCommands {

    private final IslandLocationService islandLocationService;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Supplier<@Nullable IslandLimitService> limitServiceProvider;
    private final Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider;
    private final Supplier<@Nullable IslandBoosterService> boosterServiceProvider;
    private final Supplier<@Nullable IslandBoosterMenu> boosterMenuProvider;
    private final Supplier<@Nullable IslandMissionsMenu> missionsMenuProvider;
    private final Supplier<@Nullable IslandBoundaryService> boundaryServiceProvider;

    public IslandMechanicsCommands(
            IslandLocationService islandLocationService,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Supplier<@Nullable IslandLimitService> limitServiceProvider,
            Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider,
            Supplier<@Nullable IslandBoosterService> boosterServiceProvider,
            Supplier<@Nullable IslandBoosterMenu> boosterMenuProvider,
            Supplier<@Nullable IslandMissionsMenu> missionsMenuProvider,
            Supplier<@Nullable IslandBoundaryService> boundaryServiceProvider) {
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.limitServiceProvider =
                Objects.requireNonNull(limitServiceProvider, "limitServiceProvider must not be null");
        this.antiAbuseServiceProvider =
                Objects.requireNonNull(antiAbuseServiceProvider, "antiAbuseServiceProvider must not be null");
        this.boosterServiceProvider =
                Objects.requireNonNull(boosterServiceProvider, "boosterServiceProvider must not be null");
        this.boosterMenuProvider = Objects.requireNonNull(boosterMenuProvider, "boosterMenuProvider must not be null");
        this.missionsMenuProvider =
                Objects.requireNonNull(missionsMenuProvider, "missionsMenuProvider must not be null");
        this.boundaryServiceProvider =
                Objects.requireNonNull(boundaryServiceProvider, "boundaryServiceProvider must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildLimits() {
        return Cmd.literal("limits").executes(this::executeLimits);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildQuarantine() {
        return Cmd.literal("quarantine").executes(this::executeQuarantine);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBooster() {
        return Cmd.literal("booster")
                .executes(this::executeBooster)
                .then(Cmd.literal("apply")
                        .then(Cmd.argument("category", StringArgumentType.word())
                                .then(Cmd.argument("multiplier", DoubleArgumentType.doubleArg(1.0))
                                        .then(Cmd.argument("duration", StringArgumentType.word())
                                                .executes(this::executeAdminApplyBooster)))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildMissions() {
        return Cmd.literal("missions").executes(this::executeMissions);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildChallenges() {
        return Cmd.literal("challenges").executes(this::executeMissions);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBorder() {
        return Cmd.literal("border").executes(this::executeBorder);
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildBounds() {
        return Cmd.literal("bounds").executes(this::executeBorder);
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

    private int executeLimits(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can view island limits.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandLimitService limitService = limitServiceProvider.get();
        if (limitService == null) {
            send(player, Component.text("Island limits subsystem is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You must have an active profile to view island limits.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                send(player, Component.text("You do not belong to an active island.", NamedTextColor.RED));
                return;
            }

            IslandId islandId = optIslandId.get();
            java.util.Map<LimitType, Integer> counts = limitService.getCounts(islandId);
            java.util.Map<LimitType, Integer> limits = limitService.getLimits(islandId);

            send(
                    player,
                    MiniMessage.miniMessage()
                            .deserialize(
                                    "<gradient:#00e5ff:#0077ff><bold>--- Island Hardware & Anti-Lag Limits ---</bold></gradient>"));
            send(
                    player,
                    MiniMessage.miniMessage().deserialize("<yellow><bold>Tile Entities & Redstone:</bold></yellow>"));
            for (LimitType type : LimitType.values()) {
                if (type.category() == LimitCategory.TILE_ENTITY && limits.containsKey(type)) {
                    int c = counts.getOrDefault(type, 0);
                    int m = limits.get(type);
                    String color = c >= m ? "<red>" : (c >= m * 0.8 ? "<gold>" : "<aqua>");
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(" <gray>•</gray> <white>" + type.name() + "</white>: " + color + c
                                            + "</color><gray> / </gray><green>" + m + "</green>"));
                }
            }

            send(
                    player,
                    MiniMessage.miniMessage().deserialize("<yellow><bold>Living Entities & Vehicles:</bold></yellow>"));
            for (LimitType type : LimitType.values()) {
                if (type.category() == LimitCategory.ENTITY && limits.containsKey(type)) {
                    int c = counts.getOrDefault(type, 0);
                    int m = limits.get(type);
                    String color = c >= m ? "<red>" : (c >= m * 0.8 ? "<gold>" : "<aqua>");
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(" <gray>•</gray> <white>" + type.name() + "</white>: " + color + c
                                            + "</color><gray> / </gray><green>" + m + "</green>"));
                }
            }
        });

        return Cmd.OK;
    }

    private int executeQuarantine(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can check quarantine status.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandAntiAbuseService antiAbuseService = antiAbuseServiceProvider.get();
        if (antiAbuseService == null) {
            send(player, Component.text("Starter quarantine protection is disabled on this node.", NamedTextColor.RED));
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
            Optional<Duration> optRemaining = antiAbuseService.getQuarantineRemaining(islandId, Instant.now());
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                if (optRemaining.isPresent()) {
                    Duration remaining = optRemaining.get();
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(
                                            "<gold>Island Starter Quarantine:</gold> <yellow><bold>ACTIVE</bold></yellow> "
                                                    + "(<white>" + formatDuration(remaining)
                                                    + "</white> remaining)<newline>"
                                                    + "<gray>Visitor access and dropping starter items are prohibited during quarantine.</gray>"));
                } else {
                    send(
                            player,
                            MiniMessage.miniMessage()
                                    .deserialize(
                                            "<gold>Island Starter Quarantine:</gold> <green><bold>INACTIVE</bold></green> "
                                                    + "<gray>(Full visitor access and trade enabled)</gray>"));
                }
            });
        });
        return Cmd.OK;
    }

    private int executeBooster(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can access the booster menu.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandBoosterMenu boosterMenu = boosterMenuProvider.get();
        if (boosterMenu == null) {
            send(player, Component.text("Island boosters are disabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        boosterMenu.open(player);
        return Cmd.OK;
    }

    private int executeAdminApplyBooster(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        IslandBoosterService boosterService = boosterServiceProvider.get();
        if (boosterService == null) {
            send(sender, Component.text("Island boosters are disabled on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }
        if (!sender.hasPermission("uxmskyblock.admin.booster")) {
            send(sender, Component.text("You do not have permission to apply boosters.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String catStr = StringArgumentType.getString(ctx, "category");
        Optional<BoosterCategory> optCategory = BoosterCategory.parse(catStr);
        if (optCategory.isEmpty()) {
            send(
                    sender,
                    Component.text(
                            "Invalid booster category: " + catStr
                                    + ". Available: SPAWNER_RATE, CROP_GROWTH, ORE_GENERATOR, MOB_EXP, ISLAND_WORTH, MISSION_REWARDS",
                            NamedTextColor.RED));
            return Cmd.OK;
        }

        double multiplier = DoubleArgumentType.getDouble(ctx, "multiplier");
        String durStr = StringArgumentType.getString(ctx, "duration");
        Duration duration = parseDurationString(durStr);
        if (duration.isZero() || duration.isNegative()) {
            send(sender, Component.text("Invalid duration: " + durStr, NamedTextColor.RED));
            return Cmd.OK;
        }

        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Console must specify an island to apply boosters.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<IslandId> optIsland = islandLocationService.findIslandId(optProfile.get());
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        Instant now = Instant.now();
        BoosterApplyResult result = boosterService.applyBooster(islandId, optCategory.get(), multiplier, duration, now);

        send(
                player,
                MiniMessage.miniMessage()
                        .deserialize(
                                "<green>Successfully applied <yellow>" + multiplier + "x</yellow> booster to <gold>"
                                        + optCategory.get().displayName() + "</gold> for <white>"
                                        + formatDuration(duration) + "</white>! Result: "
                                        + result.getClass().getSimpleName() + "</green>"));
        return Cmd.OK;
    }

    private int executeMissions(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can view missions.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandMissionsMenu missionsMenu = missionsMenuProvider.get();
        if (missionsMenu == null) {
            send(player, Component.text("Missions are not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> missionsMenu.open(player));
        return Cmd.OK;
    }

    private int executeBorder(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can toggle border view.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandBoundaryService boundaryService = boundaryServiceProvider.get();
        if (boundaryService == null) {
            send(player, Component.text("Island boundary visualization is not currently enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        PlayerUuid uuid = new PlayerUuid(player.getUniqueId());
        boolean active = boundaryService.togglePerimeter(uuid);
        if (active) {
            send(
                    player,
                    Component.text(
                            "Perimeter particle projection enabled. Outlines will project around your island boundary.",
                            NamedTextColor.AQUA));
        } else {
            send(player, Component.text("Perimeter particle projection disabled.", NamedTextColor.YELLOW));
        }
        return Cmd.OK;
    }

    private static Duration parseDurationString(String raw) {
        if (raw == null || raw.isBlank()) {
            return Duration.ZERO;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        try {
            if (s.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            if (s.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
            }
            return Duration.ofSeconds(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }

    private static String formatDuration(Duration duration) {
        if (duration.isNegative() || duration.isZero()) {
            return "0s";
        }
        long seconds = duration.toSeconds();
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format("%dm %ds", minutes, secs);
        }
        return String.format("%ds", secs);
    }
}
