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
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
    private final Messages messages;

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
            String worldName,
            Messages messages) {
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
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
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
            send(src.getSender(), "admin.inactivity_disabled");
            return Cmd.OK;
        }

        send(src.getSender(), "admin.inactivity_scanning");
        schedulerPort.async(() -> {
            try {
                IslandInactivityScanReport report = inactivityService.scanWorld(worldName, Instant.now());
                send(
                        src.getSender(),
                        "admin.inactivity_report",
                        Placeholder.unparsed("evaluated", Integer.toString(report.totalEvaluated())),
                        Placeholder.unparsed("successions", Integer.toString(report.successionsExecuted())),
                        Placeholder.unparsed("archived", Integer.toString(report.islandsArchived())),
                        Placeholder.unparsed("deleted", Integer.toString(report.islandsDeleted())),
                        Placeholder.unparsed("skipped", Integer.toString(report.islandsSkipped())));
            } catch (Exception e) {
                send(
                        src.getSender(),
                        "admin.inactivity_failed",
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminFreeze(CommandContext<CommandSourceStack> ctx, String reason) {
        CommandSourceStack src = ctx.getSource();
        IslandAdminFreezeService freezeService = freezeServiceProvider.get();
        if (freezeService == null) {
            send(src.getSender(), "admin.freeze_disabled");
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        String actor = src.getSender().getName();

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optId.isEmpty()) {
                send(src.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optId.get();
            try {
                freezeService.freezeIsland(islandId, reason, actor);
                protectionListener.invalidateIsland(islandId);
                send(
                        src.getSender(),
                        "admin.frozen",
                        Placeholder.unparsed("island", islandId.value().toString()),
                        Placeholder.unparsed("reason", reason));
            } catch (Exception e) {
                send(
                        src.getSender(),
                        "admin.freeze_failed",
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            }
        });
        return Cmd.OK;
    }

    private int executeAdminUnfreeze(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandAdminFreezeService freezeService = freezeServiceProvider.get();
        if (freezeService == null) {
            send(src.getSender(), "admin.freeze_disabled");
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        String actor = src.getSender().getName();

        schedulerPort.async(() -> {
            Optional<IslandId> optId = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optId.isEmpty()) {
                send(src.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optId.get();
            try {
                freezeService.unfreezeIsland(islandId, actor);
                protectionListener.invalidateIsland(islandId);
                send(
                        src.getSender(),
                        "admin.unfrozen",
                        Placeholder.unparsed("island", islandId.value().toString()));
            } catch (Exception e) {
                send(
                        src.getSender(),
                        "admin.unfreeze_failed",
                        Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
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
                send(src.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optId.get();
            IslandAdminFreezeService fService = freezeServiceProvider.get();
            if (fService == null) {
                send(src.getSender(), "admin.freeze_not_configured");
                return;
            }
            Optional<Island> optIsland = fService.findIsland(islandId);
            if (optIsland.isEmpty()) {
                send(
                        src.getSender(),
                        "admin.island_record_missing",
                        Placeholder.unparsed("island", islandId.value().toString()));
                return;
            }

            Island island = optIsland.get();
            Optional<IslandLocation> optLoc = fService.findLocation(islandId);

            Audience audience = src.getSender();
            send(
                    audience,
                    "admin.inspect_header",
                    Placeholder.unparsed("island", islandId.value().toString()));
            send(
                    audience,
                    "admin.inspect_owner",
                    Placeholder.unparsed(
                            "owner", island.ownerPlayerUuid().value().toString()));
            send(
                    audience,
                    "admin.inspect_lifecycle",
                    Placeholder.unparsed("state", island.lifecycle().name()));
            send(
                    audience,
                    "admin.inspect_economic",
                    Placeholder.unparsed("state", island.economicState().name()));
            Component administrative = messages.renderPlain(
                    audience, island.isFrozen() ? "admin.inspect_state_frozen" : "admin.inspect_state_normal");
            send(audience, "admin.inspect_administrative", Placeholder.component("state", administrative));
            if (island.isFrozen()) {
                String reason = island.freezeReason();
                TagResolver reasonTag = reason != null
                        ? Placeholder.unparsed("reason", reason)
                        : Placeholder.component(
                                "reason", messages.renderPlain(audience, "admin.inspect_no_freeze_reason"));
                send(audience, "admin.inspect_freeze_reason", reasonTag);
            }
            send(
                    audience,
                    "admin.inspect_membership",
                    Placeholder.unparsed(
                            "members", Integer.toString(island.members().size())),
                    Placeholder.unparsed(
                            "roles", Integer.toString(island.roles().size())));
            optLoc.ifPresent(loc -> send(
                    audience,
                    "admin.inspect_location",
                    Placeholder.unparsed("world", loc.worldName()),
                    Placeholder.unparsed("x", Integer.toString(loc.bounds().centerX())),
                    Placeholder.unparsed("z", Integer.toString(loc.bounds().centerZ())),
                    Placeholder.unparsed("radius", Integer.toString(loc.bounds().radius()))));
        });
        return Cmd.OK;
    }

    private int executeAdminRestore(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        if (!sender.hasPermission("uxmskyblock.admin.restore")
                && !sender.hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                && !sender.isOp()) {
            send(sender, "admin.restore_no_permission");
            return Cmd.OK;
        }

        IslandRestoreService rService = restoreServiceProvider.get();
        if (rService == null) {
            send(sender, "admin.restore_not_configured");
            return Cmd.OK;
        }

        String backupIdStr = StringArgumentType.getString(ctx, "backupId");
        send(sender, "admin.restore_starting", Placeholder.unparsed("backup", backupIdStr));

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
                    send(sender, "admin.restore_manifest_missing", Placeholder.unparsed("backup", backupIdStr));
                    return;
                }

                BackupManifest manifest = optManifest.get();
                IslandRestoreService.RestoreOutcome outcome =
                        rService.executeRestore(manifest, bucket, rootPrefix, true);
                if (outcome instanceof IslandRestoreService.RestoreOutcome.Success success) {
                    send(
                            sender,
                            "admin.restore_success",
                            Placeholder.unparsed("backup", backupIdStr),
                            Placeholder.unparsed("artifacts", Integer.toString(success.artifactsRestored())));
                } else if (outcome instanceof IslandRestoreService.RestoreOutcome.Failure failure) {
                    send(
                            sender,
                            "admin.restore_failed",
                            Placeholder.unparsed("backup", backupIdStr),
                            Placeholder.unparsed("reason", failure.reason()));
                }
            } catch (Exception e) {
                send(sender, "admin.restore_error", Placeholder.unparsed("reason", String.valueOf(e.getMessage())));
            }
        });

        return Cmd.OK;
    }

    private int executeAdminDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(src.getSender(), "admin.recycle_disabled");
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optIsland.isEmpty()) {
                send(src.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optIsland.get();
            send(
                    src.getSender(),
                    "admin.delete_starting",
                    Placeholder.unparsed("island", islandId.value().toString()));
            var unusedAdminReset = recycleService
                    .executeReset(new ProfileId(UUID.randomUUID()), islandId, null, true)
                    .thenAccept(result -> {
                        schedulerPort.onGlobal(() -> {
                            if (result instanceof IslandRecycleService.RecycleResult.Success) {
                                send(
                                        src.getSender(),
                                        "admin.delete_success",
                                        Placeholder.unparsed(
                                                "island", islandId.value().toString()));
                            } else {
                                send(
                                        src.getSender(),
                                        "admin.delete_failed",
                                        Placeholder.unparsed(
                                                "island", islandId.value().toString()));
                            }
                        });
                    });
        });
        return Cmd.OK;
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
}
