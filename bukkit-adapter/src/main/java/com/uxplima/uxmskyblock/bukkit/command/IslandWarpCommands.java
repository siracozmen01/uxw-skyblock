package com.uxplima.uxmskyblock.bukkit.command;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.bukkit.Location;
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
import com.uxplima.uxmskyblock.bukkit.menu.IslandWarpBrowseMenu;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.warp.BukkitSafeBlockInspector;
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.warp.IslandWarpService;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.warp.IslandClosedToVisitorsException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandLockedException;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;
import com.uxplima.uxmskyblock.core.domain.warp.PlayerBannedFromIslandException;
import com.uxplima.uxmskyblock.core.domain.warp.UnsafeTeleportDestinationException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpCategory;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLocation;
import com.uxplima.uxmskyblock.core.domain.warp.WarpLockedException;
import com.uxplima.uxmskyblock.core.domain.warp.WarpName;
import com.uxplima.uxmskyblock.core.domain.warp.WarpNotFoundException;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is warp}: the points on an island other players may travel to.
 *
 * <p>The warp service, its table, its per warp lock, its ban list and its anti trap safe spot
 * search have all been here since the warp work, and no command reached any of it. Every warp
 * feature this plugin advertises was unreachable: a player could not make one, list one or go to
 * one.
 */
public final class IslandWarpCommands {

    private static final int PAGE_SIZE = 20;

    private final Supplier<@Nullable IslandWarpService> warpServiceProvider;
    private final IslandLocationService islandLocationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;

    /** The directory as a window. Absent on a node whose menus are off, and then the lines are sent. */
    private volatile @Nullable IslandWarpBrowseMenu browseMenu;

    /** Where a new warp is written down for the island's members to read. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group which window draws the public directory. */
    public void useBrowseMenu(@Nullable IslandWarpBrowseMenu browseMenu) {
        this.browseMenu = browseMenu;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandWarpCommands(
            Supplier<@Nullable IslandWarpService> warpServiceProvider,
            IslandLocationService islandLocationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.warpServiceProvider = Objects.requireNonNull(warpServiceProvider, "warpServiceProvider must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("warp")
                .executes(this::executeList)
                .then(Cmd.literal("list").executes(this::executeList))
                .then(Cmd.literal("browse")
                        .executes(ctx -> executeBrowse(ctx, null))
                        .then(Cmd.argument("category", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    for (WarpCategory category : WarpCategory.values()) {
                                        builder.suggest(category.name().toLowerCase(java.util.Locale.ROOT));
                                    }
                                    return builder.buildFuture();
                                })
                                .executes(this::executeBrowseInCategory)))
                .then(Cmd.literal("visit")
                        .then(Cmd.argument("owner", StringArgumentType.word())
                                .then(Cmd.argument("name", StringArgumentType.word())
                                        .executes(this::executeVisitWarp))))
                .then(Cmd.literal("create")
                        .then(Cmd.argument("name", StringArgumentType.word())
                                .executes(ctx -> executeCreate(ctx, WarpCategory.GENERAL))
                                .then(Cmd.argument("category", StringArgumentType.word())
                                        .executes(this::executeCreateWithCategory))))
                .then(Cmd.literal("move")
                        .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeMove)))
                .then(Cmd.literal("delete")
                        .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeDelete)))
                .then(Cmd.literal("lock")
                        .then(Cmd.argument("name", StringArgumentType.word())
                                .executes(ctx -> executeSetLock(ctx, true))))
                .then(Cmd.literal("unlock")
                        .then(Cmd.argument("name", StringArgumentType.word())
                                .executes(ctx -> executeSetLock(ctx, false))))
                .then(Cmd.literal("category")
                        .then(Cmd.argument("name", StringArgumentType.word())
                                .then(Cmd.argument("category", StringArgumentType.word())
                                        .executes(this::executeSetCategory))))
                .then(Cmd.argument("name", StringArgumentType.word()).executes(this::executeGo));
    }

    /**
     * {@code /is explore} and {@code /is warps}: the community directory, under the two names the
     * design document publishes for it.
     *
     * <p>Both are {@code /is warp browse}. The document names them and the tree did not.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildExplore(String verb) {
        return Cmd.literal(verb)
                .executes(ctx -> executeBrowse(ctx, null))
                .then(Cmd.argument("category", StringArgumentType.word()).executes(this::executeBrowseInCategory));
    }

    private int executeList(CommandContext<CommandSourceStack> ctx) {
        return onOwnIsland(ctx, (player, service, island) -> {
            List<IslandWarp> warps = service.getWarps(island.id());
            int allowed = service.getMaxAllowedWarps(island.id());
            onEntity(player, () -> {
                send(
                        player,
                        "warp.header",
                        Placeholder.unparsed("count", Integer.toString(warps.size())),
                        Placeholder.unparsed("max", Integer.toString(allowed)));
                if (warps.isEmpty()) {
                    send(player, "warp.empty");
                    return;
                }
                for (IslandWarp warp : warps) {
                    send(
                            player,
                            "warp.entry",
                            Placeholder.unparsed("name", warp.name().value()),
                            Placeholder.unparsed("category", warp.category().name()),
                            Placeholder.unparsed("state", warp.isLocked() ? "locked" : "open"));
                }
            });
        });
    }

    /**
     * Takes a player to the warp they picked out of the window.
     *
     * <p>The click arrives on the thread that owns the player and everything after it is storage,
     * so it hops off first. It ends in the command's own visit flow, not a second one beside it
     * that would drift from the gate, the ban list and the safe spot search.
     */
    private void visitFromTheDirectory(Player player, IslandWarpService service, IslandWarpBrowseMenu.Entry entry) {
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return;
        }
        ProfileId profileId = optProfile.get();
        IslandId islandId = entry.warp().islandId();
        schedulerPort.async(() -> islandLocationService
                .findIsland(islandId)
                .ifPresentOrElse(
                        island -> visitResolvedIsland(
                                player,
                                service,
                                profileId,
                                island,
                                entry.ownerName(),
                                entry.warp().name().value()),
                        () -> onEntity(
                                player,
                                () -> send(
                                        player,
                                        "warp.island_unknown",
                                        Placeholder.unparsed("owner", entry.ownerName())))));
    }

    /**
     * {@code /is warp browse <category>}: the directory, narrowed to one kind of warp.
     *
     * <p>getPublicWarpsByCategory was written with the warp work and had no caller, so a player
     * looking for a shop read every public warp on the server and picked the shops out by eye.
     */
    private int executeBrowseInCategory(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "category");
        for (WarpCategory category : WarpCategory.values()) {
            if (category.name().equalsIgnoreCase(raw)) {
                return executeBrowse(ctx, category);
            }
        }
        send(
                ctx.getSource().getSender(),
                "warp.unknown_category",
                Placeholder.unparsed("category", raw),
                Placeholder.unparsed("categories", categoryNames()));
        return Cmd.OK;
    }

    private int executeBrowse(CommandContext<CommandSourceStack> ctx, @Nullable WarpCategory only) {
        return withService(
                ctx,
                (player, service, profileId) -> schedulerPort.async(() -> {
                    List<IslandWarp> warps = only == null
                            ? service.getPublicWarps(PAGE_SIZE, 0)
                            : service.getPublicWarpsByCategory(only, PAGE_SIZE, 0);
                    // The listing used to name the warp and nothing else, so a player who saw one
                    // they liked had no word to type after /is warp visit. The owner is resolved
                    // once per island rather than once per warp: a shop island with six public
                    // warps is one read, not six.
                    Map<IslandId, String> ownerNames = new HashMap<>();
                    for (IslandWarp warp : warps) {
                        ownerNames.computeIfAbsent(warp.islandId(), this::ownerNameOf);
                    }
                    IslandWarpBrowseMenu window = this.browseMenu;
                    List<IslandWarpBrowseMenu.Entry> entries = window == null
                            ? List.of()
                            : IslandWarpBrowseMenu.entriesOf(warps, id -> ownerNames.getOrDefault(id, "?"));

                    onEntity(player, () -> {
                        if (only == null) {
                            send(player, "warp.browse_header");
                        } else {
                            send(player, "warp.browse_header_category", Placeholder.unparsed("category", only.name()));
                        }
                        if (warps.isEmpty()) {
                            send(player, "warp.browse_empty");
                            return;
                        }
                        if (window != null) {
                            // The icon a warp has carried since the warp work is drawn here and
                            // nowhere else. The chat lines stay for a node whose menus are off.
                            window.show(player, entries, entry -> visitFromTheDirectory(player, service, entry));
                            return;
                        }
                        for (IslandWarp warp : warps) {
                            send(
                                    player,
                                    "warp.browse_entry",
                                    Placeholder.unparsed("name", warp.name().value()),
                                    Placeholder.unparsed("owner", ownerNames.getOrDefault(warp.islandId(), "?")),
                                    Placeholder.unparsed(
                                            "category", warp.category().name()));
                        }
                    });
                }));
    }

    /**
     * {@code /is warp move <name>}: puts an existing warp where the player is standing.
     *
     * <p>relocateWarp was written with the warp work and had no caller anywhere, so a warp put down
     * in the wrong place could only be deleted and made again, which loses whatever a visitor had
     * bookmarked about it.
     */
    private int executeMove(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        if (!(ctx.getSource().getSender() instanceof Player standing)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Location at = standing.getLocation();
        if (at == null || at.getWorld() == null) {
            send(standing, "warp.location_unreadable");
            return Cmd.OK;
        }
        // Read where the player is now, on the thread that owns them, before anything goes async.
        WarpLocation where =
                new WarpLocation(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());

        return onOwnIsland(ctx, (player, service, island) -> {
            ProfileId profileId = activeProfile(player).orElse(null);
            if (profileId == null) {
                onEntity(player, () -> send(player, "error.session_not_active"));
                return;
            }
            try {
                IslandWarp moved = service.relocateWarp(island, profileId, WarpName.of(rawName), where);
                onEntity(
                        player,
                        () -> send(
                                player,
                                "warp.moved",
                                Placeholder.unparsed("name", moved.name().value())));
            } catch (SecurityException denied) {
                onEntity(player, () -> send(player, "warp.no_permission"));
            } catch (WarpNotFoundException missing) {
                onEntity(player, () -> send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            } catch (RuntimeException refused) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "warp.refused",
                                Placeholder.unparsed("reason", String.valueOf(refused.getMessage()))));
            }
        });
    }

    private int executeCreateWithCategory(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "category");
        for (WarpCategory category : WarpCategory.values()) {
            if (category.name().equalsIgnoreCase(raw)) {
                return executeCreate(ctx, category);
            }
        }
        send(
                ctx.getSource().getSender(),
                "warp.unknown_category",
                Placeholder.unparsed("category", raw),
                Placeholder.unparsed("categories", categoryNames()));
        return Cmd.OK;
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx, WarpCategory category) {
        String rawName = StringArgumentType.getString(ctx, "name");
        if (!(ctx.getSource().getSender() instanceof Player standing)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Location at = standing.getLocation();
        if (at == null || at.getWorld() == null) {
            send(standing, "warp.location_unreadable");
            return Cmd.OK;
        }
        // Read where the player is now, on the thread that owns them, before anything goes async.
        WarpLocation where =
                new WarpLocation(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());

        return onOwnIsland(ctx, (player, service, island) -> {
            ProfileId profileId = activeProfile(player).orElse(null);
            if (profileId == null) {
                onEntity(player, () -> send(player, "error.session_not_active"));
                return;
            }
            try {
                IslandWarp warp = service.createWarp(
                        island, profileId, WarpName.of(rawName), where, category, category.defaultIconMaterial());
                activityLog.recordForMembers(
                        island.id(),
                        profileId,
                        ActivityEventType.WARP_CREATED,
                        "activity.warp_created",
                        java.util.Map.of(
                                "player",
                                player.getName(),
                                "name",
                                warp.name().value(),
                                "category",
                                warp.category().name()));
                onEntity(
                        player,
                        () -> send(
                                player,
                                "warp.created",
                                Placeholder.unparsed("name", warp.name().value()),
                                Placeholder.unparsed("category", warp.category().name())));
            } catch (SecurityException denied) {
                onEntity(player, () -> send(player, "warp.no_permission"));
            } catch (RuntimeException refused) {
                onEntity(
                        player,
                        () -> send(
                                player,
                                "warp.refused",
                                Placeholder.unparsed("reason", String.valueOf(refused.getMessage()))));
            }
        });
    }

    private int executeDelete(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        return onOwnIsland(ctx, (player, service, island) -> {
            ProfileId profileId = activeProfile(player).orElse(null);
            if (profileId == null) {
                onEntity(player, () -> send(player, "error.session_not_active"));
                return;
            }
            try {
                service.deleteWarp(island, profileId, WarpName.of(rawName));
                onEntity(player, () -> send(player, "warp.deleted", Placeholder.unparsed("name", rawName)));
            } catch (SecurityException denied) {
                onEntity(player, () -> send(player, "warp.no_permission"));
            } catch (RuntimeException refused) {
                onEntity(player, () -> send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            }
        });
    }

    /**
     * {@code /is warplock <name>}: the short form the design document publishes.
     *
     * <p>Shutting one warp to visitors while the others stay open is a rule the visit gate has read
     * since it was written, and nothing could set it. {@code /is warp lock} and this are the same
     * verb; the document names this one, so it exists.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildWarpLock() {
        return Cmd.literal("warplock")
                .then(Cmd.argument("name", StringArgumentType.word()).executes(ctx -> executeSetLock(ctx, true)));
    }

    /** {@code /is warpunlock <name>}: the other half of the short form. */
    public LiteralArgumentBuilder<CommandSourceStack> buildWarpUnlock() {
        return Cmd.literal("warpunlock")
                .then(Cmd.argument("name", StringArgumentType.word()).executes(ctx -> executeSetLock(ctx, false)));
    }

    /**
     * Shuts one warp to visitors, or opens it again.
     *
     * <p>{@code IslandWarpService.setWarpLock} had no caller anywhere in the plugin, so
     * {@code warp.isLocked()} was a column the visit gate read and nothing ever wrote: every warp on
     * every island was open, whatever the owner wanted.
     */
    private int executeSetLock(CommandContext<CommandSourceStack> ctx, boolean locked) {
        String rawName = StringArgumentType.getString(ctx, "name");
        return onOwnIsland(ctx, (player, service, island) -> {
            try {
                service.setWarpLock(island, ownerProfileOf(island, player), WarpName.of(rawName), locked);
                onEntity(
                        player,
                        () -> send(
                                player,
                                locked ? "warp.locked" : "warp.unlocked",
                                Placeholder.unparsed("name", rawName)));
            } catch (SecurityException denied) {
                onEntity(player, () -> send(player, "warp.no_permission"));
            } catch (RuntimeException missing) {
                onEntity(player, () -> send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            }
        });
    }

    /**
     * Moves a warp between the directory categories a visitor browses by.
     *
     * <p>{@code setWarpCategory} was the other half of the same dead pair: a warp could be created
     * in a category and never moved out of it.
     */
    private int executeSetCategory(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        String rawCategory = StringArgumentType.getString(ctx, "category");
        WarpCategory category = null;
        for (WarpCategory candidate : WarpCategory.values()) {
            if (candidate.name().equalsIgnoreCase(rawCategory)) {
                category = candidate;
            }
        }
        if (category == null) {
            send(
                    ctx.getSource().getSender(),
                    "warp.unknown_category",
                    Placeholder.unparsed("category", rawCategory),
                    Placeholder.unparsed("categories", categoryNames()));
            return Cmd.OK;
        }

        WarpCategory chosen = category;
        return onOwnIsland(ctx, (player, service, island) -> {
            try {
                service.setWarpCategory(island, ownerProfileOf(island, player), WarpName.of(rawName), chosen);
                onEntity(
                        player,
                        () -> send(
                                player,
                                "warp.category_changed",
                                Placeholder.unparsed("name", rawName),
                                Placeholder.unparsed("category", chosen.name())));
            } catch (SecurityException denied) {
                onEntity(player, () -> send(player, "warp.no_permission"));
            } catch (RuntimeException missing) {
                onEntity(player, () -> send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            }
        });
    }

    /** The caller's own profile on this island, which onOwnIsland has already proved they have. */
    private ProfileId ownerProfileOf(Island island, Player player) {
        return activeProfile(player).orElse(island.ownerProfileId());
    }

    private int executeGo(CommandContext<CommandSourceStack> ctx) {
        String rawName = StringArgumentType.getString(ctx, "name");
        return onOwnIsland(ctx, (player, service, island) -> {
            Optional<IslandWarp> optWarp = service.getWarp(island.id(), WarpName.of(rawName));
            if (optWarp.isEmpty()) {
                onEntity(player, () -> send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
                return;
            }
            WarpLocation where = optWarp.get().location();
            onEntity(player, () -> {
                org.bukkit.World world = org.bukkit.Bukkit.getWorld(where.worldName());
                if (world == null) {
                    send(player, "warp.world_unloaded", Placeholder.unparsed("world", where.worldName()));
                    return;
                }
                Location target = new Location(world, where.x(), where.y(), where.z(), where.yaw(), where.pitch());
                var unused = player.teleportAsync(target);
                send(player, "warp.travelled", Placeholder.unparsed("name", rawName));
            });
        });
    }

    /**
     * {@code /is warp visit <owner> <name>}: travelling to somebody else's public warp.
     *
     * <p>{@code /is warp browse} has listed public warps since the warp work and a player could not
     * go to a single one of them. The ban list, the island lock, the per warp lock and the anti trap
     * safe spot search all sat behind {@code prepareVisit}, which nothing in the plugin ever called,
     * so the whole visitor security surface was dead code around a listing nobody could act on.
     *
     * <p>The gate and the warp row are storage, so they are read on the scheduler thread. The safe
     * spot search reads blocks, and under Folia a block belongs to the region thread that owns it,
     * so that is a second hop rather than part of the first.
     */
    private int executeVisitWarp(CommandContext<CommandSourceStack> ctx) {
        String owner = StringArgumentType.getString(ctx, "owner");
        String rawName = StringArgumentType.getString(ctx, "name");

        return withService(
                ctx,
                (player, service, profileId) -> schedulerPort.async(() -> {
                    Optional<IslandId> optTarget =
                            IslandAdminCommands.resolveIslandId(sessionCoordinator, islandLocationService, owner);
                    Optional<Island> optIsland = optTarget.flatMap(islandLocationService::findIsland);
                    if (optIsland.isEmpty()) {
                        onEntity(
                                player,
                                () -> send(player, "warp.island_unknown", Placeholder.unparsed("owner", owner)));
                        return;
                    }

                    visitResolvedIsland(player, service, profileId, optIsland.get(), owner, rawName);
                }));
    }

    /**
     * Takes a player to one warp on an island that has already been found.
     *
     * <p>The gate, the ban list and the warp row are storage, so this expects to be off the thread
     * the request arrived on. It is public because the browse window needs exactly this and
     * duplicating it there would be two flows to keep in step: one of them would drift.
     */
    public void visitResolvedIsland(
            Player player,
            IslandWarpService service,
            ProfileId profileId,
            Island island,
            String owner,
            String rawName) {
        IslandWarp warp;
        try {
            warp = service.resolveVisit(island, new PlayerUuid(player.getUniqueId()), profileId, WarpName.of(rawName));
        } catch (PlayerBannedFromIslandException banned) {
            onEntity(player, () -> send(player, "warp.visit_banned", Placeholder.unparsed("owner", owner)));
            return;
        } catch (IslandLockedException locked) {
            onEntity(player, () -> send(player, "warp.visit_locked", Placeholder.unparsed("owner", owner)));
            return;
        } catch (IslandClosedToVisitorsException closed) {
            onEntity(player, () -> send(player, "warp.visit_closed", Placeholder.unparsed("owner", owner)));
            return;
        } catch (WarpLockedException warpLocked) {
            onEntity(player, () -> send(player, "warp.visit_warp_locked", Placeholder.unparsed("name", rawName)));
            return;
        } catch (RuntimeException missing) {
            onEntity(player, () -> send(player, "warp.unknown", Placeholder.unparsed("name", rawName)));
            return;
        }

        travelToSafeSpot(player, service, warp, rawName);
    }

    /** Finds the safe spot on the thread that owns the destination, then puts the player on it. */
    private void travelToSafeSpot(Player player, IslandWarpService service, IslandWarp warp, String rawName) {
        WarpLocation requested = warp.location();
        schedulerPort.onRegion(
                requested.worldName(),
                (int) Math.floor(requested.x()) >> 4,
                (int) Math.floor(requested.z()) >> 4,
                () -> {
                    WarpLocation safe;
                    try {
                        safe = service.safeSpotFor(warp, new BukkitSafeBlockInspector());
                    } catch (UnsafeTeleportDestinationException unsafe) {
                        onEntity(player, () -> send(player, "teleport.unsafe_destination"));
                        return;
                    }
                    onEntity(player, () -> {
                        org.bukkit.World world = org.bukkit.Bukkit.getWorld(safe.worldName());
                        if (world == null) {
                            send(player, "warp.world_unloaded", Placeholder.unparsed("world", safe.worldName()));
                            return;
                        }
                        Location target = new Location(world, safe.x(), safe.y(), safe.z(), safe.yaw(), safe.pitch());
                        var unused = player.teleportAsync(target);
                        send(player, "warp.travelled", Placeholder.unparsed("name", rawName));
                    });
                });
    }

    /** The name a player types after {@code /is warp visit} to reach this island. */
    private String ownerNameOf(IslandId islandId) {
        return islandLocationService
                .findIsland(islandId)
                .map(island -> {
                    String name = org.bukkit.Bukkit.getOfflinePlayer(
                                    island.ownerPlayerUuid().value())
                            .getName();
                    return name == null ? islandId.value().toString() : name;
                })
                .orElseGet(() -> islandId.value().toString());
    }

    private static String categoryNames() {
        StringBuilder out = new StringBuilder();
        for (WarpCategory category : WarpCategory.values()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(category.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** What a warp command needs: a player, the service, and the island they belong to. */
    @FunctionalInterface
    private interface IslandAction {
        void run(Player player, IslandWarpService service, Island island);
    }

    @FunctionalInterface
    private interface ServiceAction {
        void run(Player player, IslandWarpService service, ProfileId profileId);
    }

    private int withService(CommandContext<CommandSourceStack> ctx, ServiceAction action) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandWarpService service = warpServiceProvider.get();
        if (service == null) {
            send(player, "warp.disabled");
            return Cmd.OK;
        }
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        action.run(player, service, optProfile.get());
        return Cmd.OK;
    }

    /** Resolves the caller's own island off the thread, then hands it back with the service. */
    private int onOwnIsland(CommandContext<CommandSourceStack> ctx, IslandAction action) {
        return withService(
                ctx,
                (player, service, profileId) -> schedulerPort.async(() -> {
                    Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
                    if (optIslandId.isEmpty()) {
                        onEntity(player, () -> send(player, "error.no_island"));
                        return;
                    }
                    Optional<Island> optIsland = islandLocationService.findIsland(optIslandId.get());
                    if (optIsland.isEmpty()) {
                        onEntity(player, () -> send(player, "error.no_island"));
                        return;
                    }
                    action.run(player, service, optIsland.get());
                }));
    }

    private void onEntity(Player player, Runnable work) {
        schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
            if (player.isOnline()) {
                work.run();
            }
        });
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
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
