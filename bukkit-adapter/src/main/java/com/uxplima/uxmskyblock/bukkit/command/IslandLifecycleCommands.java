package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island lifecycle commands:
 * /is create, /is reset, /is delete, /is rename
 */
public final class IslandLifecycleCommands {

    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService islandLocationService;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final IslandProtectionListener protectionListener;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final String worldName;
    private final Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider;
    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    private final Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider;
    private final Supplier<@Nullable IslandNameService> nameServiceProvider;

    public IslandLifecycleCommands(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            String worldName,
            Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider,
            Supplier<@Nullable IslandRecycleService> recycleServiceProvider,
            Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider,
            Supplier<@Nullable IslandNameService> nameServiceProvider) {
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.sessionCoordinator = Objects.requireNonNull(sessionCoordinator, "sessionCoordinator must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.antiAbuseServiceProvider =
                Objects.requireNonNull(antiAbuseServiceProvider, "antiAbuseServiceProvider must not be null");
        this.recycleServiceProvider =
                Objects.requireNonNull(recycleServiceProvider, "recycleServiceProvider must not be null");
        this.resetMenuProvider = Objects.requireNonNull(resetMenuProvider, "resetMenuProvider must not be null");
        this.nameServiceProvider = Objects.requireNonNull(nameServiceProvider, "nameServiceProvider must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildCreate() {
        return Cmd.literal("create")
                .executes(
                        ctx -> executeCreate(ctx, presetCatalog.defaultPreset().id()))
                .then(Cmd.argument("preset", StringArgumentType.word())
                        .executes(ctx -> executeCreate(ctx, StringArgumentType.getString(ctx, "preset"))));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildReset() {
        return Cmd.literal("reset")
                .executes(this::executeReset)
                .then(Cmd.literal("confirm")
                        .then(Cmd.argument("code", StringArgumentType.word()).executes(this::executeResetConfirm)));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildDelete() {
        return Cmd.literal("delete")
                .executes(this::executeReset)
                .then(Cmd.literal("confirm")
                        .then(Cmd.argument("code", StringArgumentType.word()).executes(this::executeResetConfirm)));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRename() {
        return Cmd.literal("rename")
                .executes(this::executeGetRename)
                .then(Cmd.argument("name", StringArgumentType.greedyString()).executes(this::executeRename));
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    private Optional<IslandId> findIslandId(Player player) {
        return activeProfile(player).flatMap(islandLocationService::findIslandId);
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

    private int executeCreate(CommandContext<CommandSourceStack> ctx, String presetId) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can create an island.", NamedTextColor.RED));
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

        schedulerPort.async(() -> {
            CreateIslandUseCase.CreateIslandResult result =
                    createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, worldName);

            schedulerPort.onEntity(playerUuid, () -> {
                if (result instanceof CreateIslandUseCase.CreateIslandResult.Success success) {
                    protectionListener.cacheIsland(success.island());
                    IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
                    if (antiAbuse != null) {
                        antiAbuse.quarantineNewIsland(success.island().id(), Instant.now());
                    }

                    World resolvedWorld = Bukkit.getWorld(worldName);
                    if (resolvedWorld != null) {
                        int centerX = success.location().bounds().centerX();
                        int centerZ = success.location().bounds().centerZ();
                        int spawnY = 100;
                        int chunkX = centerX >> 4;
                        int chunkZ = centerZ >> 4;
                        String targetWorld = resolvedWorld.getName();

                        schedulerPort.onRegion(targetWorld, chunkX, chunkZ, () -> {
                            World w = Bukkit.getWorld(targetWorld);
                            if (w != null) {
                                schematicEngine.pastePreset(w, centerX, spawnY, centerZ, success.preset());
                            }
                            schedulerPort.onEntity(playerUuid, () -> {
                                if (!player.isOnline()) {
                                    return;
                                }
                                Location destination = new Location(
                                        w != null ? w : resolvedWorld,
                                        success.location().spawnX(),
                                        success.location().spawnY(),
                                        success.location().spawnZ(),
                                        0.0f,
                                        0.0f);
                                var unused = player.teleportAsync(destination).thenAccept(teleported -> {
                                    if (Boolean.TRUE.equals(teleported)) {
                                        player.setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                                        player.setFallDistance(0.0f);
                                    }
                                });
                                send(
                                        player,
                                        Component.text(
                                                "Island created successfully with preset '"
                                                        + success.preset().displayName() + "'!",
                                                NamedTextColor.GREEN));
                            });
                        });
                    } else {
                        send(
                                player,
                                Component.text(
                                        "Island created, but world '" + worldName + "' is not loaded on this node.",
                                        NamedTextColor.YELLOW));
                    }
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    send(
                            player,
                            Component.text(
                                    "You already own or belong to an island! Use /is home to visit it.",
                                    NamedTextColor.RED));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unknown) {
                    send(
                            player,
                            Component.text(
                                    "Unknown preset '" + unknown.presetId()
                                            + "'. Available: classic, desert, nether, cave.",
                                    NamedTextColor.RED));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.Failure failure) {
                    send(player, Component.text("Failed to create island: " + failure.reason(), NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeReset(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can reset islands.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(player, Component.text("Island recycle service is disabled.", NamedTextColor.RED));
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
            send(player, Component.text("You do not have an active island to reset.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        if (checkResetBlocked(player, antiAbuse)) {
            return Cmd.OK;
        }

        ResetChallenge challenge = recycleService.generateResetChallenge(profileId, islandId);

        send(
                player,
                Component.text(
                        "WARNING: ISLAND RESET CANNOT BE UNDONE!", NamedTextColor.DARK_RED, TextDecoration.BOLD));
        send(
                player,
                Component.text(
                        "All island blocks, chests, items, and bank balance will be permanently wiped.",
                        NamedTextColor.GRAY));
        send(
                player,
                Component.text("To confirm in chat, type: ", NamedTextColor.YELLOW)
                        .append(Component.text(
                                "/is reset confirm " + challenge.code(), NamedTextColor.GOLD, TextDecoration.BOLD)));

        IslandResetConfirmationMenu resetMenu = resetMenuProvider.get();
        if (resetMenu != null) {
            resetMenu.open(player, challenge.code());
        }
        return Cmd.OK;
    }

    private boolean checkResetBlocked(Player player, @Nullable IslandAntiAbuseService antiAbuse) {
        if (antiAbuse == null) {
            return false;
        }
        boolean bypass = player.hasPermission("skyblock.antiabuse.bypass") || player.isOp();
        ResetCheckResult check = antiAbuse.checkResetAllowed(new PlayerUuid(player.getUniqueId()), bypass);
        if (check instanceof ResetCheckResult.CooldownActive cd) {
            send(
                    player,
                    Component.text(
                            "Island reset is on cooldown. Remaining: " + formatDuration(cd.remaining()),
                            NamedTextColor.RED));
            return true;
        } else if (check instanceof ResetCheckResult.DailyLimitExceeded dl) {
            send(
                    player,
                    Component.text(
                            "You have reached the daily limit of " + dl.maxDailyResets()
                                    + " island resets. Available in: " + formatDuration(dl.remaining()),
                            NamedTextColor.RED));
            return true;
        }
        return false;
    }

    private int executeResetConfirm(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, Component.text("Only in-game players can confirm island resets.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(player, Component.text("Island recycle service is disabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String code = StringArgumentType.getString(ctx, "code");
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, Component.text("You do not have an active profile.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = optProfile.get();
        Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
        if (optIsland.isEmpty()) {
            send(player, Component.text("You do not have an active island to reset.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIsland.get();
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        if (checkResetBlocked(player, antiAbuse)) {
            return Cmd.OK;
        }

        var unused = recycleService
                .executeReset(profileId, islandId, code, false)
                .thenAccept(result -> {
                    schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                        switch (result) {
                            case RecycleResult.Success s -> {
                                if (antiAbuse != null) {
                                    antiAbuse.recordReset(new PlayerUuid(player.getUniqueId()), Instant.now());
                                    if (antiAbuse.purgeInventoryOnReset()) {
                                        player.getInventory().clear();
                                        player.getInventory().setArmorContents(null);
                                        player.getInventory().setItemInOffHand(null);
                                        player.getEnderChest().clear();
                                        player.setExp(0.0f);
                                        player.setLevel(0);
                                        player.setTotalExperience(0);
                                    }
                                }
                                player.teleport(player.getWorld().getSpawnLocation());
                                send(
                                        player,
                                        Component.text(
                                                "Your island has been reset and recycled successfully!",
                                                NamedTextColor.GREEN,
                                                TextDecoration.BOLD));
                                send(
                                        player,
                                        Component.text("Create a new island with /is create.", NamedTextColor.GRAY));
                            }
                            case RecycleResult.NotOwner no ->
                                send(
                                        player,
                                        Component.text(
                                                "Only the island owner can reset this island!", NamedTextColor.RED));
                            case RecycleResult.InvalidChallenge ic ->
                                send(
                                        player,
                                        Component.text(
                                                "Reset confirmation failed: " + ic.reason(), NamedTextColor.RED));
                            case RecycleResult.IslandNotFound nf ->
                                send(player, Component.text("Island not found.", NamedTextColor.RED));
                            case RecycleResult.Failure f ->
                                send(player, Component.text("Reset failed: " + f.reason(), NamedTextColor.RED));
                        }
                    });
                });
        return Cmd.OK;
    }

    private int executeGetRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can view or rename islands.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandNameService nameService = nameServiceProvider.get();
        if (nameService == null) {
            send(player, Component.text("Island naming service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<IslandId> optIslandId = findIslandId(player);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You must have an island to view its name.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<IslandName> current = nameService.getIslandName(optIslandId.get());
        if (current.isPresent()) {
            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize(
                            "<green>Current island name: <gold>" + current.get().value()
                                    + "</gold>. Use <gold>/is rename <new-name></gold> to change it.</green>"));
        } else {
            player.sendMessage(
                    MiniMessage.miniMessage()
                            .deserialize(
                                    "<yellow>Your island has no custom name. Use <gold>/is rename <name></gold> to set one.</yellow>"));
        }
        return Cmd.OK;
    }

    private int executeRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can rename islands.", NamedTextColor.RED));
            return Cmd.OK;
        }
        IslandNameService nameService = nameServiceProvider.get();
        if (nameService == null) {
            send(player, Component.text("Island naming service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<IslandId> optIslandId = findIslandId(player);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You must have an island to rename it.", NamedTextColor.RED));
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED));
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        String rawName = StringArgumentType.getString(ctx, "name");

        try {
            IslandName newName = nameService.renameIsland(optIslandId.get(), profileId, rawName);
            player.sendMessage(MiniMessage.miniMessage()
                    .deserialize(
                            "<green>Island successfully renamed to: <gold>" + newName.value() + "</gold>!</green>"));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            player.sendMessage(MiniMessage.miniMessage().deserialize("<red>" + e.getMessage() + "</red>"));
        }
        return Cmd.OK;
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
