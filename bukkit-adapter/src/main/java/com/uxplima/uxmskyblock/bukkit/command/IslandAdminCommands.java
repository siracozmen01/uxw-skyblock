package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.domain.backup.BackupCatalogRecord;
import com.uxplima.uxmskyblock.core.domain.backup.BackupManifest;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * Handles administrative island commands:
 * /is admin [inactivity|freeze|unfreeze|inspect|restore|delete]
 * and /is restore
 */
public final class IslandAdminCommands {

    private final Supplier<@Nullable IslandInactivityService> inactivityServiceProvider;
    private final Supplier<@Nullable IslandAdminFreezeService> freezeServiceProvider;
    private final Supplier<@Nullable IslandRestoreService> restoreServiceProvider;
    private final Supplier<@Nullable BackupService> backupServiceProvider;
    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    private final IslandProtectionListener protectionListener;
    private final IslandLocationService islandLocationService;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final String worldName;

    public IslandAdminCommands(
            Supplier<@Nullable IslandInactivityService> inactivityServiceProvider,
            Supplier<@Nullable IslandAdminFreezeService> freezeServiceProvider,
            Supplier<@Nullable IslandRestoreService> restoreServiceProvider,
            Supplier<@Nullable BackupService> backupServiceProvider,
            Supplier<@Nullable IslandRecycleService> recycleServiceProvider,
            IslandProtectionListener protectionListener,
            IslandLocationService islandLocationService,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            String worldName) {
        this.inactivityServiceProvider =
                Objects.requireNonNull(inactivityServiceProvider, "inactivityServiceProvider must not be null");
        this.freezeServiceProvider =
                Objects.requireNonNull(freezeServiceProvider, "freezeServiceProvider must not be null");
        this.restoreServiceProvider =
                Objects.requireNonNull(restoreServiceProvider, "restoreServiceProvider must not be null");
        this.backupServiceProvider =
                Objects.requireNonNull(backupServiceProvider, "backupServiceProvider must not be null");
        this.recycleServiceProvider =
                Objects.requireNonNull(recycleServiceProvider, "recycleServiceProvider must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildAdmin() {
        return Cmd.literal("admin")
                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_INSPECT.node())
                        || src.getSender().isOp())
                .then(Cmd.literal("inactivity").then(Cmd.literal("scan").executes(this::executeAdminInactivityScan)))
                .then(Cmd.literal("freeze")
                        .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                || src.getSender().isOp())
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .executes(ctx -> executeAdminFreeze(ctx, "Administrative quarantine"))
                                .then(Cmd.argument("reason", StringArgumentType.greedyString())
                                        .executes(ctx ->
                                                executeAdminFreeze(ctx, StringArgumentType.getString(ctx, "reason"))))))
                .then(Cmd.literal("unfreeze")
                        .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                || src.getSender().isOp())
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(this::executeAdminUnfreeze)))
                .then(Cmd.literal("inspect")
                        .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_INSPECT.node())
                                || src.getSender().hasPermission(CatalogPermissions.ADMIN_FREEZE.node())
                                || src.getSender().isOp())
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(this::executeAdminInspect)))
                .then(Cmd.literal("restore")
                        .requires(src -> src.getSender().hasPermission("uxmskyblock.admin.restore")
                                || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                                || src.getSender().isOp())
                        .then(Cmd.argument("backupId", StringArgumentType.word())
                                .executes(this::executeAdminRestore)))
                .then(Cmd.literal("delete")
                        .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                                || src.getSender().isOp())
                        .then(Cmd.argument("target", StringArgumentType.word()).executes(this::executeAdminDelete)));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRestore() {
        return Cmd.literal("restore")
                .requires(src -> src.getSender().hasPermission("uxmskyblock.admin.restore")
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                .then(Cmd.argument("backupId", StringArgumentType.word()).executes(this::executeAdminRestore));
    }

    public static Optional<IslandId> resolveIslandId(
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            IslandLocationService islandLocationService,
            String target) {
        try {
            return Optional.of(IslandId.of(UUID.fromString(target)));
        } catch (IllegalArgumentException notUuid) {
            Player online = Bukkit.getPlayerExact(target);
            if (online != null && sessionCoordinator != null) {
                Optional<ProfileId> optProfile = sessionCoordinator.activeProfile(online.getUniqueId());
                if (optProfile.isPresent()) {
                    Optional<IslandId> id = islandLocationService.findIslandId(optProfile.get());
                    if (id.isPresent()) {
                        return id;
                    }
                }
            }
            if (sessionCoordinator != null) {
                @SuppressWarnings("deprecation")
                OfflinePlayer offline = Bukkit.getOfflinePlayer(target);
                if (offline.hasPlayedBefore() || offline.isOnline()) {
                    Optional<ProfileId> optProfile = sessionCoordinator.findDurableActiveProfile(offline.getUniqueId());
                    if (optProfile.isPresent()) {
                        return islandLocationService.findIslandId(optProfile.get());
                    }
                }
            }
            return Optional.empty();
        }
    }

    private int executeAdminInactivityScan(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandInactivityService inactivityService = inactivityServiceProvider.get();
        if (inactivityService == null) {
            send(src.getSender(), Component.text("Inactivity service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        send(src.getSender(), Component.text("Starting asynchronous island inactivity scan...", NamedTextColor.YELLOW));
        schedulerPort.async(() -> {
            try {
                IslandInactivityScanReport report = inactivityService.scanWorld(worldName, Instant.now());
                send(
                        src.getSender(),
                        Component.text(
                                String.format(
                                        "Inactivity scan complete: %d evaluated, %d successions, %d archived, %d deleted, %d skipped.",
                                        report.totalEvaluated(),
                                        report.successionsExecuted(),
                                        report.islandsArchived(),
                                        report.islandsDeleted(),
                                        report.islandsSkipped()),
                                NamedTextColor.GREEN));
            } catch (Exception e) {
                send(src.getSender(), Component.text("Inactivity scan failed: " + e.getMessage(), NamedTextColor.RED));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminFreeze(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSourceStack src = ctx.getSource();
        IslandAdminFreezeService freezeService = freezeServiceProvider.get();
        if (freezeService == null) {
            send(src.getSender(), Component.text("Freeze service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        String actor = src.getSender().getName();

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optId.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not resolve island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optId.get();
            try {
                freezeService.freezeIsland(islandId, reason, actor);
                protectionListener.invalidateIsland(islandId);
                send(
                        src.getSender(),
                        MiniMessage.miniMessage()
                                .deserialize(
                                        "<green>Successfully quarantined and froze island <yellow>" + islandId.value()
                                                + "</yellow> with reason: <aqua>" + reason + "</aqua></green>"));
            } catch (Exception e) {
                send(src.getSender(), Component.text("Failed to freeze island: " + e.getMessage(), NamedTextColor.RED));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminUnfreeze(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandAdminFreezeService freezeService = freezeServiceProvider.get();
        if (freezeService == null) {
            send(src.getSender(), Component.text("Freeze service is not enabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        String actor = src.getSender().getName();

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optId.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not resolve island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optId.get();
            try {
                freezeService.unfreezeIsland(islandId, actor);
                protectionListener.invalidateIsland(islandId);
                send(
                        src.getSender(),
                        MiniMessage.miniMessage()
                                .deserialize(
                                        "<green>Successfully lifted administrative quarantine and unfroze island <yellow>"
                                                + islandId.value() + "</yellow></green>"));
            } catch (Exception e) {
                send(
                        src.getSender(),
                        Component.text("Failed to unfreeze island: " + e.getMessage(), NamedTextColor.RED));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminInspect(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String target = StringArgumentType.getString(ctx, "target");

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optId.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not resolve island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optId.get();
            IslandAdminFreezeService fService = freezeServiceProvider.get();
            if (fService == null) {
                send(
                        src.getSender(),
                        Component.text("Island admin freeze service is not configured.", NamedTextColor.RED));
                return;
            }
            Optional<Island> optIsland = fService.findIsland(islandId);
            if (optIsland.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Island record not found: " + islandId.value(), NamedTextColor.RED));
                return;
            }

            Island island = optIsland.get();
            Optional<IslandLocation> optLoc = fService.findLocation(islandId);

            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gold>--- Island Inspection: <yellow>" + islandId.value()
                                    + "</yellow> ---</gold>"));
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Owner UUID: <white>"
                                    + island.ownerPlayerUuid().value() + "</white></gray>"));
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Lifecycle: <green>"
                                    + island.lifecycle().name() + "</green></gray>"));
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Economic State: <aqua>"
                                    + island.economicState().name() + "</aqua></gray>"));
            String adminColor = island.isFrozen() ? "<red><bold>FROZEN</bold></red>" : "<green>NORMAL</green>";
            send(
                    src.getSender(),
                    MiniMessage.miniMessage().deserialize("<gray>Administrative State: " + adminColor + "</gray>"));
            if (island.isFrozen()) {
                send(
                        src.getSender(),
                        MiniMessage.miniMessage()
                                .deserialize("<gray>Freeze Reason: <yellow>"
                                        + (island.freezeReason() != null ? island.freezeReason() : "None")
                                        + "</yellow></gray>"));
            }
            send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize(
                                    "<gray>Members: <white>" + island.members().size() + "</white> | Roles: <white>"
                                            + island.roles().size() + "</white></gray>"));
            optLoc.ifPresent(loc -> send(
                    src.getSender(),
                    MiniMessage.miniMessage()
                            .deserialize("<gray>Location: <white>" + loc.worldName() + " ("
                                    + loc.bounds().centerX() + ", "
                                    + loc.bounds().centerZ() + ") radius="
                                    + loc.bounds().radius() + "</white></gray>")));
        });
        return Cmd.OK;
    }

    private int executeAdminRestore(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("uxmskyblock.admin.restore")
                && !sender.hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                && !sender.isOp()) {
            send(sender, Component.text("You do not have permission to restore island backups.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandRestoreService rService = restoreServiceProvider.get();
        if (rService == null) {
            send(sender, Component.text("Island restore service is not configured on this node.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String backupIdStr = StringArgumentType.getString(ctx, "backupId");
        send(sender, Component.text("Initiating restore for backup ID: " + backupIdStr + "...", NamedTextColor.YELLOW));

        schedulerPort.async(() -> {
            try {
                BackupSetId backupSetId = BackupSetId.fromString(backupIdStr);
                StorageBucket bucket = new StorageBucket("uxmskyblock-backups");
                String rootPrefix = "backups/" + backupSetId;

                Optional<BackupManifest> optManifest = Optional.empty();
                BackupService bService = backupServiceProvider.get();
                if (bService != null) {
                    optManifest = bService.loadManifest(bucket, rootPrefix);
                }

                if (optManifest.isEmpty()) {
                    Optional<BackupCatalogRecord> optRecord =
                            rService.catalogPort().findById(backupSetId);
                    if (optRecord.isPresent() && bService != null) {
                        BackupCatalogRecord record = optRecord.get();
                        String customPrefix = "backups/" + record.targetRootTypeId() + "/" + record.targetRootKey()
                                + "/" + backupSetId;
                        optManifest = bService.loadManifest(bucket, customPrefix);
                        if (optManifest.isPresent()) {
                            rootPrefix = customPrefix;
                        }
                    }
                }

                if (optManifest.isEmpty()) {
                    send(
                            sender,
                            Component.text(
                                    "Failed to locate valid backup manifest for " + backupIdStr + " in storage.",
                                    NamedTextColor.RED));
                    return;
                }

                BackupManifest manifest = optManifest.get();
                IslandRestoreService.RestoreOutcome outcome =
                        rService.executeRestore(manifest, bucket, rootPrefix, true);
                if (outcome instanceof IslandRestoreService.RestoreOutcome.Success success) {
                    send(
                            sender,
                            Component.text(
                                    "Successfully restored backup " + backupIdStr + " (" + success.artifactsRestored()
                                            + " artifacts restored).",
                                    NamedTextColor.GREEN));
                } else if (outcome instanceof IslandRestoreService.RestoreOutcome.Failure failure) {
                    send(
                            sender,
                            Component.text(
                                    "Failed to restore backup " + backupIdStr + ": " + failure.reason(),
                                    NamedTextColor.RED));
                }
            } catch (Exception e) {
                send(sender, Component.text("Error executing restore: " + e.getMessage(), NamedTextColor.RED));
            }
        });

        return Cmd.OK;
    }

    private int executeAdminDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(src.getSender(), Component.text("Island recycle service is disabled.", NamedTextColor.RED));
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optIsland.isEmpty()) {
                send(
                        src.getSender(),
                        Component.text("Could not find island for target: " + target, NamedTextColor.RED));
                return;
            }

            IslandId islandId = optIsland.get();
            send(
                    src.getSender(),
                    Component.text(
                            "Initiating administrative deletion of island " + islandId.value() + "...",
                            NamedTextColor.YELLOW));
            var unusedAdminReset = recycleService
                    .executeReset(new ProfileId(UUID.randomUUID()), islandId, null, true)
                    .thenAccept(result -> {
                        schedulerPort.onGlobal(() -> {
                            if (result instanceof IslandRecycleService.RecycleResult.Success) {
                                send(
                                        src.getSender(),
                                        Component.text(
                                                "Island " + islandId.value()
                                                        + " was deleted and recycled successfully.",
                                                NamedTextColor.GREEN));
                            } else {
                                send(
                                        src.getSender(),
                                        Component.text(
                                                "Administrative deletion failed for island " + islandId.value(),
                                                NamedTextColor.RED));
                            }
                        });
                    });
        });
        return Cmd.OK;
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
}
