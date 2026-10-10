package com.uxplima.uxmskyblock.bukkit.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.schematic.Schematic;
import com.uxplima.uxmlib.schematic.Vec3i;
import com.uxplima.uxmlib.schematic.nbt.NbtCompound;
import com.uxplima.uxmlib.schematic.paper.CaptureOptions;
import com.uxplima.uxmlib.schematic.paper.PasteReport;
import com.uxplima.uxmlib.schematic.paper.Rotation;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.permission.CatalogPermissions;
import com.uxplima.uxmskyblock.bukkit.schematic.IslandSchematics;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * {@code /is schematic}: an operator saves a box of the world as a schematic and pastes one, with nothing but
 * this plugin installed.
 *
 * <p>Two corners are marked where the operator stands, {@code pos1} and {@code pos2}. {@code save} keeps the
 * box between them around the block the operator stands on, the block a preset's players arrive standing on,
 * so an island saved here and named by a preset pastes where its players arrive. {@code paste} puts one down
 * around the block the operator stands on, turned if asked. The work runs a chunk at a time on the thread
 * that owns each chunk, and its result comes back as a line in chat.
 */
public final class IslandSchematicCommands {

    private static final List<String> TURNS = List.of("0", "90", "180", "270");

    private final Supplier<@Nullable IslandSchematics> schematics;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final Map<UUID, Corners> corners = new ConcurrentHashMap<>();

    /** The corners one operator has marked, each in the world it was marked in. */
    private record Corners(@Nullable Corner first, @Nullable Corner second) {}

    private record Corner(UUID world, Vec3i at) {}

    public IslandSchematicCommands(
            Supplier<@Nullable IslandSchematics> schematics, SchedulerPort schedulerPort, Messages messages) {
        this.schematics = Objects.requireNonNull(schematics, "schematics must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Cmd.literal("schematic")
                .requires(src -> src.getSender().hasPermission(CatalogPermissions.ADMIN_SCHEMATIC.node())
                        || src.getSender().hasPermission(CatalogPermissions.ADMIN_MANAGE.node())
                        || src.getSender().isOp())
                .executes(ctx -> usage(ctx.getSource().getSender()))
                .then(Cmd.literal("pos1").executes(ctx -> mark(ctx, true)))
                .then(Cmd.literal("pos2").executes(ctx -> mark(ctx, false)))
                .then(Cmd.literal("save")
                        .executes(ctx -> usage(ctx.getSource().getSender()))
                        .then(Cmd.argument("name", StringArgumentType.word()).executes(this::save)))
                .then(Cmd.literal("paste")
                        .executes(ctx -> usage(ctx.getSource().getSender()))
                        .then(Cmd.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    IslandSchematics files = schematics.get();
                                    if (files == null) {
                                        return builder.buildFuture();
                                    }
                                    String typed = builder.getRemainingLowerCase();
                                    return files.list().handle((names, failure) -> {
                                        if (names != null) {
                                            names.stream()
                                                    .filter(name -> name.startsWith(typed))
                                                    .forEach(builder::suggest);
                                        }
                                        return builder.build();
                                    });
                                })
                                .executes(ctx -> paste(ctx, "0"))
                                .then(Cmd.argument("rotation", StringArgumentType.word())
                                        .suggests((ctx, builder) -> {
                                            TURNS.stream()
                                                    .filter(turn -> turn.startsWith(builder.getRemaining()))
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(ctx -> paste(ctx, StringArgumentType.getString(ctx, "rotation"))))))
                .then(Cmd.literal("list").executes(this::list));
    }

    private int usage(Audience sender) {
        send(sender, "schematic.usage");
        return Cmd.OK;
    }

    private int mark(CommandContext<CommandSourceStack> ctx, boolean first) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        Location here = player.getLocation();
        if (here == null) {
            send(player, "schematic.location_unreadable");
            return Cmd.OK;
        }
        Corner corner =
                new Corner(here.getWorld().getUID(), new Vec3i(here.getBlockX(), here.getBlockY(), here.getBlockZ()));
        corners.merge(
                player.getUniqueId(),
                first ? new Corners(corner, null) : new Corners(null, corner),
                (had, now) -> new Corners(
                        now.first() != null ? now.first() : had.first(),
                        now.second() != null ? now.second() : had.second()));
        send(
                player,
                first ? "schematic.first_corner" : "schematic.second_corner",
                Placeholder.unparsed("x", String.valueOf(corner.at().x())),
                Placeholder.unparsed("y", String.valueOf(corner.at().y())),
                Placeholder.unparsed("z", String.valueOf(corner.at().z())));
        return Cmd.OK;
    }

    private int save(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandSchematics files = schematics.get();
        if (files == null) {
            send(player, "schematic.unavailable");
            return Cmd.OK;
        }
        Optional<String> name = IslandSchematics.nameOf(StringArgumentType.getString(ctx, "name"));
        if (name.isEmpty()) {
            send(player, "schematic.bad_name");
            return Cmd.OK;
        }
        Corners marked = corners.get(player.getUniqueId());
        Corner first = marked == null ? null : marked.first();
        Corner second = marked == null ? null : marked.second();
        if (first == null || second == null) {
            send(player, "schematic.corners_missing");
            return Cmd.OK;
        }
        World world = player.getWorld();
        if (!first.world().equals(world.getUID()) || !second.world().equals(world.getUID())) {
            send(player, "schematic.corners_elsewhere");
            return Cmd.OK;
        }
        Location here = player.getLocation();
        if (here == null) {
            send(player, "schematic.location_unreadable");
            return Cmd.OK;
        }
        Vec3i standingOn = new Vec3i(here.getBlockX(), here.getBlockY() - 1, here.getBlockZ());
        NbtCompound metadata = NbtCompound.builder()
                .putString("Name", name.get())
                .putString("Author", player.getName())
                .putLong("Date", System.currentTimeMillis())
                .build();
        send(player, "schematic.saving", Placeholder.unparsed("name", name.get()));
        var unused = files.save(
                        name.get(),
                        world,
                        first.at(),
                        second.at(),
                        standingOn,
                        CaptureOptions.DEFAULT.withMetadata(metadata))
                .whenComplete((saved, failure) -> {
                    if (failure != null) {
                        send(player, "schematic.save_failed", Placeholder.unparsed("reason", reason(failure)));
                        return;
                    }
                    send(
                            player,
                            "schematic.saved",
                            Placeholder.unparsed("name", name.get()),
                            Placeholder.unparsed("size", size(saved)),
                            Placeholder.unparsed("blocks", String.valueOf(saved.volume())),
                            Placeholder.unparsed(
                                    "block_entities",
                                    String.valueOf(saved.blockEntities().size())),
                            Placeholder.unparsed(
                                    "entities", String.valueOf(saved.entities().size())));
                });
        return Cmd.OK;
    }

    private int paste(CommandContext<CommandSourceStack> ctx, String turn) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), "error.players_only");
            return Cmd.OK;
        }
        IslandSchematics files = schematics.get();
        if (files == null) {
            send(player, "schematic.unavailable");
            return Cmd.OK;
        }
        Optional<String> name = IslandSchematics.nameOf(StringArgumentType.getString(ctx, "name"));
        if (name.isEmpty()) {
            send(player, "schematic.bad_name");
            return Cmd.OK;
        }
        Rotation rotation;
        try {
            rotation = Rotation.ofDegrees(Integer.parseInt(turn.trim()));
        } catch (IllegalArgumentException notATurn) {
            send(player, "schematic.bad_rotation", Placeholder.unparsed("rotation", turn));
            return Cmd.OK;
        }
        Location here = player.getLocation();
        if (here == null) {
            send(player, "schematic.location_unreadable");
            return Cmd.OK;
        }
        Location at = new Location(here.getWorld(), here.getBlockX(), here.getBlockY() - 1, here.getBlockZ());
        send(player, "schematic.pasting", Placeholder.unparsed("name", name.get()));
        var unused = files.read(IslandSchematics.pathOf(name.get()))
                .thenCompose(found -> {
                    if (found.isEmpty()) {
                        send(player, "schematic.not_found", Placeholder.unparsed("name", name.get()));
                        return java.util.concurrent.CompletableFuture.completedFuture(null);
                    }
                    return files.paste(found.get(), at, rotation)
                            .thenAccept(report -> pasted(player, name.get(), report));
                })
                .exceptionally(failure -> {
                    send(player, "schematic.paste_failed", Placeholder.unparsed("reason", reason(failure)));
                    return null;
                });
        return Cmd.OK;
    }

    private void pasted(Player player, String name, PasteReport report) {
        send(
                player,
                "schematic.pasted",
                Placeholder.unparsed("name", name),
                Placeholder.unparsed("blocks", String.valueOf(report.blocksPlaced())),
                Placeholder.unparsed("entities", String.valueOf(report.entitiesSpawned())));
        if (!report.statesChanged().isEmpty()) {
            send(
                    player,
                    "schematic.pasted_changed",
                    Placeholder.unparsed(
                            "count", String.valueOf(report.statesChanged().size())));
        }
        if (!report.statesUnknown().isEmpty()) {
            send(
                    player,
                    "schematic.pasted_unknown",
                    Placeholder.unparsed(
                            "count", String.valueOf(report.statesUnknown().size())),
                    Placeholder.unparsed("states", String.join(", ", first(report.statesUnknown()))));
        }
        if (!report.blockEntitiesNotCarried().isEmpty()
                || !report.entitiesRefused().isEmpty()) {
            send(
                    player,
                    "schematic.pasted_defaults",
                    Placeholder.unparsed(
                            "kinds",
                            String.join(
                                    ", ",
                                    first(java.util.stream.Stream.concat(
                                                    report.blockEntitiesNotCarried().stream(),
                                                    report.entitiesRefused().stream())
                                            .sorted()
                                            .toList()))));
        }
        if (report.blocksOutsideWorld() > 0) {
            send(
                    player,
                    "schematic.pasted_outside",
                    Placeholder.unparsed("count", String.valueOf(report.blocksOutsideWorld())));
        }
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        Audience sender = ctx.getSource().getSender();
        IslandSchematics files = schematics.get();
        if (files == null) {
            send(sender, "schematic.unavailable");
            return Cmd.OK;
        }
        var unused = files.list().whenComplete((names, failure) -> {
            if (failure != null) {
                send(sender, "schematic.list_failed", Placeholder.unparsed("reason", reason(failure)));
            } else if (names.isEmpty()) {
                send(sender, "schematic.list_empty");
            } else {
                send(
                        sender,
                        "schematic.list",
                        Placeholder.unparsed("count", String.valueOf(names.size())),
                        Placeholder.unparsed("names", String.join(", ", names)));
            }
        });
        return Cmd.OK;
    }

    /** The first few of a long list, so a line in chat stays a line. */
    private static List<String> first(List<String> all) {
        return all.size() <= 5 ? all : all.subList(0, 5);
    }

    private static String size(Schematic schematic) {
        return schematic.width() + "x" + schematic.height() + "x" + schematic.length();
    }

    /** What went wrong, in the words of whatever refused, for an operator to read. */
    private static String reason(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage());
    }

    private void send(Audience audience, String key, TagResolver... resolvers) {
        var line = messages.render(audience, key, resolvers);
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
