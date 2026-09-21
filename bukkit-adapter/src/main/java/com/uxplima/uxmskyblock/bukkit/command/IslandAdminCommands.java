package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
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
import com.uxplima.uxmskyblock.core.application.backup.IslandBackupService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.snapshot.IslandRestoreService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inactivity.IslandInactivityScanReport;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
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
    private final IslandProtectionListener protectionListener;
    private final IslandLocationService islandLocationService;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final String worldName;
    private final Messages messages;
    private final IslandAdminRestoreCommands restoreCommands;

    public IslandAdminCommands(
            Supplier<@Nullable IslandInactivityService> inactivityServiceProvider,
            Supplier<@Nullable IslandAdminFreezeService> freezeServiceProvider,
            Supplier<@Nullable IslandRestoreService> restoreServiceProvider,
            Supplier<@Nullable BackupService> backupServiceProvider,
            Supplier<@Nullable IslandRecycleService> recycleServiceProvider,
            Supplier<@Nullable StorageBucket> backupBucketProvider,
            Supplier<@Nullable IslandBackupService> islandBackupServiceProvider,
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
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.restoreCommands = new IslandAdminRestoreCommands(
                restoreServiceProvider,
                backupServiceProvider,
                recycleServiceProvider,
                backupBucketProvider,
                islandBackupServiceProvider,
                islandLocationService,
                sessionCoordinator,
                schedulerPort,
                messages);
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
                                .executes(ctx -> restoreCommands.executeAdminRestore(ctx, RestoreMode.safeDefault()))
                                .then(Cmd.argument("mode", StringArgumentType.word())
                                        .executes(restoreCommands::executeAdminRestoreWithMode))))
                .then(restoreCommands.buildRollback())
                .then(restoreCommands.buildBackup())
                .then(Cmd.literal("delete")
                        .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                                || src.getSender().isOp())
                        .then(Cmd.argument("target", StringArgumentType.word())
                                .executes(restoreCommands::executeAdminDelete)));
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

    /**
     * The mode decides how much comes back. A caller who names none gets the one that changes
     * least, because the destructive default is the one nobody meant to pick.
     */
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

    /** The standalone /is restore branch, which is the same command reachable without /is admin. */
    public LiteralArgumentBuilder<CommandSourceStack> buildRestore() {
        return restoreCommands.buildRestore();
    }
}
