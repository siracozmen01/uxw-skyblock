package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.backup.BackupService;
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
import com.uxplima.uxmskyblock.core.domain.snapshot.RestoreMode;
import com.uxplima.uxmskyblock.core.domain.storage.StorageBucket;
import org.jspecify.annotations.Nullable;

/**
 * The two administrative commands that cannot be undone: restore and delete.
 *
 * <p>They sat among the ones that can, so freezing an island and erasing one read the same on the
 * page. These two write over a world and the other four do not.
 *
 * <p>A restore never rewinds money. Which tables it does put back is the caller's choice among the
 * three {@link RestoreMode} values, and none of the three writes a bank.
 */
public final class IslandAdminRestoreCommands {

    private final Supplier<@Nullable IslandRestoreService> restoreServiceProvider;
    private final Supplier<@Nullable BackupService> backupServiceProvider;
    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    private final IslandLocationService islandLocationService;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Messages messages;

    public IslandAdminRestoreCommands(
            Supplier<@Nullable IslandRestoreService> restoreServiceProvider,
            Supplier<@Nullable BackupService> backupServiceProvider,
            Supplier<@Nullable IslandRecycleService> recycleServiceProvider,
            IslandLocationService islandLocationService,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages) {
        this.restoreServiceProvider =
                Objects.requireNonNull(restoreServiceProvider, "restoreServiceProvider must not be null");
        this.backupServiceProvider =
                Objects.requireNonNull(backupServiceProvider, "backupServiceProvider must not be null");
        this.recycleServiceProvider =
                Objects.requireNonNull(recycleServiceProvider, "recycleServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRestore() {
        return Cmd.literal("restore")
                .requires(src -> src.getSender().hasPermission("uxmskyblock.admin.restore")
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                .then(Cmd.argument("backupId", StringArgumentType.word())
                        .executes(ctx -> executeAdminRestore(ctx, RestoreMode.safeDefault()))
                        .then(Cmd.argument("mode", StringArgumentType.word())
                                .executes(this::executeAdminRestoreWithMode)));
    }

    public int executeAdminRestoreWithMode(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "mode");
        for (RestoreMode mode : RestoreMode.values()) {
            if (mode.name().equalsIgnoreCase(raw)) {
                return executeAdminRestore(ctx, mode);
            }
        }
        send(
                ctx.getSource().getSender(),
                "admin.restore_unknown_mode",
                Placeholder.unparsed("mode", raw),
                Placeholder.unparsed("modes", availableRestoreModes()));
        return Cmd.OK;
    }

    private static String availableRestoreModes() {
        return java.util.Arrays.stream(RestoreMode.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public int executeAdminRestore(CommandContext<CommandSourceStack> ctx, RestoreMode mode) {
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
        send(
                sender,
                "admin.restore_starting",
                Placeholder.unparsed("backup", backupIdStr),
                Placeholder.unparsed("mode", mode.name()));

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
                        rService.executeRestore(manifest, bucket, rootPrefix, true, mode);
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

    public int executeAdminDelete(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        IslandRecycleService recycleService = recycleServiceProvider.get();
        if (recycleService == null) {
            send(src.getSender(), "admin.recycle_disabled");
            return Cmd.OK;
        }

        String target = StringArgumentType.getString(ctx, "target");
        schedulerPort.async(() -> {
            Optional<IslandId> optIsland =
                    IslandAdminCommands.resolveIslandId(sessionCoordinator, islandLocationService, target);
            if (optIsland.isEmpty()) {
                send(src.getSender(), "admin.island_unresolved", Placeholder.unparsed("target", target));
                return;
            }

            IslandId islandId = optIsland.get();
            send(
                    src.getSender(),
                    "admin.delete_starting",
                    Placeholder.unparsed("island", islandId.value().toString()));
            // An admin bypass skips the ownership and challenge checks, so the requester is only ever
            // read when the bypass is off. It used to be a fresh random UUID: a profile that has
            // never existed, in the one field that says who asked for the island to be erased. The
            // admin's own profile is the true answer, and the island's owner is the honest fallback
            // when the sender is the console and has none.
            ProfileId requester = adminProfile(src.getSender()).orElse(islandOwnerOrPlaceholder(islandId));
            var unusedAdminReset = recycleService
                    .executeReset(requester, islandId, null, true)
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

    /** The profile of the staff member who typed the command, when a player typed it. */
    private Optional<ProfileId> adminProfile(CommandSender sender) {
        if (sessionCoordinator == null || !(sender instanceof Player player)) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }

    /**
     * The island's own owner, for a console delete that has no staff profile behind it.
     *
     * <p>Naming the owner is not naming the actor, and it is still better than a UUID that belongs
     * to nobody: every profile this returns is one that exists.
     */
    private ProfileId islandOwnerOrPlaceholder(IslandId islandId) {
        return islandLocationService.findOwnerProfileId(islandId).orElseGet(() -> new ProfileId(islandId.value()));
    }
}
