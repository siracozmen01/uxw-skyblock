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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.webmap.IslandMarkerSynchroniser;
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
    private volatile @Nullable IslandMarkerSynchroniser markerSynchroniser;
    private final PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final String worldName;
    private final Supplier<@Nullable IslandAntiAbuseService> antiAbuseServiceProvider;
    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    private final Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider;
    private final Supplier<@Nullable IslandNameService> nameServiceProvider;
    private final Messages messages;

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
            Supplier<@Nullable IslandNameService> nameServiceProvider,
            Messages messages) {
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
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /**
     * Hands this command the thing that draws islands on the web map.
     *
     * <p>Set after construction because the map adapters are built in the integration layer, which
     * is assembled after the commands are.
     */
    public void useMarkerSynchroniser(@Nullable IslandMarkerSynchroniser markerSynchroniser) {
        this.markerSynchroniser = markerSynchroniser;
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

    private void send(Audience audience, String key, TagResolver... resolvers) {
        send(audience, messages.render(audience, key, resolvers));
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
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();

        schedulerPort.async(() -> {
            CreateIslandUseCase.CreateIslandResult result =
                    createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, worldName);

            schedulerPort.onEntity(playerUuid, () -> {
                if (result instanceof CreateIslandUseCase.CreateIslandResult.Success success) {
                    protectionListener.cacheIsland(success.island());
                    IslandMarkerSynchroniser markers = this.markerSynchroniser;
                    if (markers != null) {
                        markers.onIslandCreated(success.island().id());
                    }
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
                                        "create.success",
                                        Placeholder.unparsed(
                                                "preset", success.preset().displayName()));
                            });
                        });
                    } else {
                        send(player, "create.world_unloaded", Placeholder.unparsed("world", worldName));
                    }
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.AlreadyHasIsland) {
                    send(player, "create.already_has_island");
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.UnknownPreset unknown) {
                    send(
                            player,
                            "create.unknown_preset",
                            Placeholder.unparsed("preset", unknown.presetId()),
                            Placeholder.unparsed("presets", availablePresetIds()));
                } else if (result instanceof CreateIslandUseCase.CreateIslandResult.Failure failure) {
                    send(player, "create.failed", Placeholder.unparsed("reason", failure.reason()));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeReset(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(player, "reset.service_disabled");
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
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        if (checkResetBlocked(player, antiAbuse)) {
            return Cmd.OK;
        }

        ResetChallenge challenge = recycleService.generateResetChallenge(profileId, islandId);

        send(player, "reset.warning");
        send(player, "reset.warning_detail");
        send(player, "reset.confirm_hint", Placeholder.unparsed("code", challenge.code()));

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
            send(player, "reset.cooldown", Placeholder.unparsed("remaining", formatDuration(cd.remaining())));
            return true;
        } else if (check instanceof ResetCheckResult.DailyLimitExceeded dl) {
            send(
                    player,
                    "reset.daily_limit",
                    Placeholder.unparsed("max", Integer.toString(dl.maxDailyResets())),
                    Placeholder.unparsed("remaining", formatDuration(dl.remaining())));
            return true;
        }
        return false;
    }

    private int executeResetConfirm(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        if (!(sender instanceof Player player)) {
            send(sender, "error.players_only");
            return Cmd.OK;
        }
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(player, "reset.service_disabled");
            return Cmd.OK;
        }

        String code = StringArgumentType.getString(ctx, "code");
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
                                send(player, "reset.success");
                                send(player, "reset.success_hint");
                            }
                            case RecycleResult.NotOwner no -> send(player, "reset.not_owner");
                            case RecycleResult.InvalidChallenge ic ->
                                send(player, "reset.invalid_challenge", Placeholder.unparsed("reason", ic.reason()));
                            case RecycleResult.IslandNotFound nf -> send(player, "reset.island_not_found");
                            case RecycleResult.Failure f ->
                                send(player, "reset.failed", Placeholder.unparsed("reason", f.reason()));
                        }
                    });
                });
        return Cmd.OK;
    }

    private int executeGetRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandNameService nameService = nameServiceProvider.get();
        if (nameService == null) {
            send(player, "name.service_disabled");
            return Cmd.OK;
        }
        Optional<IslandId> optIslandId = findIslandId(player);
        if (optIslandId.isEmpty()) {
            send(player, "name.requires_island");
            return Cmd.OK;
        }
        Optional<IslandName> current = nameService.getIslandName(optIslandId.get());
        if (current.isPresent()) {
            send(
                    player,
                    "name.current",
                    Placeholder.unparsed("name", current.get().value()));
        } else {
            send(player, "name.unset");
        }
        return Cmd.OK;
    }

    private int executeRename(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandNameService nameService = nameServiceProvider.get();
        if (nameService == null) {
            send(player, "name.service_disabled");
            return Cmd.OK;
        }
        Optional<IslandId> optIslandId = findIslandId(player);
        if (optIslandId.isEmpty()) {
            send(player, "name.requires_island");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        String rawName = StringArgumentType.getString(ctx, "name");

        try {
            IslandName newName = nameService.renameIsland(optIslandId.get(), profileId, rawName);
            send(player, "name.renamed", Placeholder.unparsed("name", newName.value()));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            send(player, "name.rename_failed", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
        }
        return Cmd.OK;
    }

    /** The preset ids an operator can actually pass, read off the catalogue rather than typed. */
    private String availablePresetIds() {
        return presetCatalog.allPresets().stream()
                .map(preset -> preset.id())
                .collect(java.util.stream.Collectors.joining(", "));
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
