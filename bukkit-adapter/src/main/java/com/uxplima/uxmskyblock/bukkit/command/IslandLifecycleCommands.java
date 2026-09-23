package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
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
import com.uxplima.uxmskyblock.core.application.activity.ActivityFeedService;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.name.IslandNameService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.activity.ActivityEventType;
import com.uxplima.uxmskyblock.core.domain.antiabuse.ResetCheckResult;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.name.IslandName;
import com.uxplima.uxmskyblock.core.domain.name.IslandNameRefusedException;
import com.uxplima.uxmskyblock.core.domain.recycle.ResetChallenge;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;

/**
 * Handles island lifecycle commands:
 * /is create, /is reset, /is delete, /is rename
 */
public final class IslandLifecycleCommands {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(IslandLifecycleCommands.class.getName());

    /**
     * What the operator wrote for the milestones this command group reaches.
     *
     * <p>The reply a command gives is the catalogue's. What a server wants beside it, a sound, a
     * title, a bar, is the operator's list and nothing here decides it. A node with no list fires
     * nothing, which is a server that wrote none.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects;

    private volatile com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer effectPlayer;

    /** Tells this command group what the operator wrote for its milestones. */
    public void useEffects(
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffects effects,
            com.uxplima.uxmskyblock.bukkit.effect.@Nullable InteractionEffectPlayer player) {
        this.effects = effects;
        this.effectPlayer = player;
    }

    /** Fires one milestone for one player, when the operator wrote anything for it. */
    private void fireMilestone(String interaction, org.bukkit.entity.Player player) {
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffects written = this.effects;
        com.uxplima.uxmskyblock.bukkit.effect.InteractionEffectPlayer plays = this.effectPlayer;
        if (written != null && plays != null) {
            plays.fire(written, interaction, player);
        }
    }

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

    /**
     * The bypass permissions the operator named.
     *
     * <p>The node was written into the code as {@code skyblock.antiabuse.bypass} while the file
     * named {@code uxmskyblock.bypass.resetlimits}, so whatever the operator granted their staff
     * did nothing and whatever they wrote in the file was never asked for.
     */
    private volatile com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration antiAbuseRules;

    private final Supplier<@Nullable IslandRecycleService> recycleServiceProvider;
    private final Supplier<@Nullable IslandResetConfirmationMenu> resetMenuProvider;
    private final Supplier<@Nullable IslandNameService> nameServiceProvider;
    private final Messages messages;

    /** Where the preset a new island started from is written down as its feed's first line. */
    private final IslandActivityLog activityLog = new IslandActivityLog();

    /** Tells this command group which permissions the operator lets staff bypass the rules with. */
    public void useAntiAbuseRules(com.uxplima.uxmskyblock.bukkit.config.@Nullable AntiAbuseConfiguration rules) {
        this.antiAbuseRules = rules;
    }

    /** Tells this command group where to write the island's activity feed. */
    public void useActivityFeed(@Nullable ActivityFeedService service) {
        this.activityLog.useService(service);
    }

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

    /**
     * {@code /is disband}: the word the documents use for erasing the island.
     *
     * <p>It is {@code /is delete} with another name, confirmation code and all. A document that
     * tells an operator to type a word is a document the command tree has to answer.
     */
    public LiteralArgumentBuilder<CommandSourceStack> buildDisband() {
        return Cmd.literal("disband")
                .executes(this::executeReset)
                .then(Cmd.literal("confirm")
                        .then(Cmd.argument("code", StringArgumentType.word()).executes(this::executeResetConfirm)));
    }

    public LiteralArgumentBuilder<CommandSourceStack> buildRename() {
        return Cmd.literal("rename")
                .executes(this::executeGetRename)
                .then(Cmd.argument("name", StringArgumentType.greedyString()).executes(this::executeRename));
    }

    /**
     * The preset's name in the player's own language.
     *
     * <p>A preset's name is a catalogue key, because it is a sentence a player reads and no sentence
     * a player reads is written in Java. A file that holds a plain name rather than a key still
     * works: the name is shown as it stands.
     */
    private String presetName(Player player, com.uxplima.uxmskyblock.core.domain.preset.StarterPreset preset) {
        return messages.words(player, preset.displayName());
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
                    activityLog.recordForMembers(
                            success.island().id(),
                            profileId,
                            ActivityEventType.TEMPLATE_APPLIED,
                            "activity.template_applied",
                            java.util.Map.of(
                                    "player",
                                    player.getName(),
                                    "preset",
                                    success.preset().id()));
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
                                        Placeholder.unparsed("preset", presetName(player, success.preset())));
                                fireMilestone("island-created", player);
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
                    // The reason is an exception's message, which can be a database's own words. It
                    // goes to the log; the player reads a line in their own language.
                    LOGGER.warning(() -> "Creating an island for " + player.getName() + " failed: " + failure.reason());
                    send(player, "create.failed");
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
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        // The permission is read here, on the thread that owns the player, and carried into the
        // scheduler. Everything after it is storage.
        boolean bypass = mayBypassTheResetRules(player);

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            if (checkResetBlocked(player, antiAbuse, bypass)) {
                return;
            }

            ResetChallenge challenge = recycleService.generateResetChallenge(profileId, islandId);

            send(player, "reset.warning");
            send(player, "reset.warning_detail");
            send(player, "reset.confirm_hint", Placeholder.unparsed("code", challenge.code()));

            IslandResetConfirmationMenu resetMenu = resetMenuProvider.get();
            if (resetMenu != null) {
                // Opening an inventory belongs to the thread that owns the player.
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        resetMenu.open(
                                player,
                                challenge.code(),
                                () -> resetWithCode(player, recycleService, challenge.code()));
                    }
                });
            }
        });
        return Cmd.OK;
    }

    private boolean checkResetBlocked(Player player, @Nullable IslandAntiAbuseService antiAbuse, boolean bypass) {
        if (antiAbuse == null) {
            return false;
        }
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
        resetWithCode(player, recycleService, code);
        return Cmd.OK;
    }

    /**
     * Erases the player's island once they have given the code, under every rule a reset is held to.
     *
     * <p>The typed confirmation and the confirmation window both come here. The window's button
     * erased the island on its own, so a reset from the window was never counted against the daily
     * allowance, had no guard against a double click and never emptied the inventory the operator
     * asked to have emptied.
     */
    private void resetWithCode(Player player, IslandRecycleService recycleService, String code) {
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return;
        }

        ProfileId profileId = optProfile.get();
        IslandAntiAbuseService antiAbuse = antiAbuseServiceProvider.get();
        boolean bypass = mayBypassTheResetRules(player);

        schedulerPort.async(() -> {
            Optional<IslandId> optIsland = islandLocationService.findIslandId(profileId);
            if (optIsland.isEmpty()) {
                send(player, "error.no_island");
                return;
            }
            IslandId islandId = optIsland.get();
            if (checkResetBlocked(player, antiAbuse, bypass)) {
                return;
            }
            // The daily limit is recorded when the erasure finishes, seconds after it is checked. A
            // second confirmation arriving in between passed the same check, because nothing had
            // been recorded yet, and erased the island twice for one allowance.
            if (antiAbuse != null && !antiAbuse.beginReset(new PlayerUuid(player.getUniqueId()))) {
                send(player, "reset.already_running");
                return;
            }
            confirmReset(player, recycleService, antiAbuse, profileId, islandId, code);
        });
    }

    /** Erases the island and reports it, once the caller has been read off the database. */
    private void confirmReset(
            Player player,
            IslandRecycleService recycleService,
            @Nullable IslandAntiAbuseService antiAbuse,
            ProfileId profileId,
            IslandId islandId,
            String code) {
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        var unused = recycleService
                .executeReset(profileId, islandId, code, false)
                // Given back whether the erasure finished or threw, so a failure does not leave the
                // player unable to reset for as long as the server runs.
                .whenComplete((result, error) -> {
                    if (antiAbuse != null) {
                        antiAbuse.endReset(playerUuid);
                    }
                })
                .thenAccept(result -> {
                    // Counted here, off any player's thread, whether or not the player is still on.
                    // It was counted on the player's thread, so a player who left while the island
                    // was erased was never counted, and the count was a database write on the region.
                    if (result instanceof RecycleResult.Success && antiAbuse != null) {
                        antiAbuse.recordReset(playerUuid, Instant.now());
                        // Owed first, paid below if the player is still here, and otherwise when
                        // they next play: a player who left kept everything they carried.
                        if (antiAbuse.purgeInventoryOnReset()) {
                            antiAbuse.oweInventoryPurge(playerUuid);
                        }
                    }
                    schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                        switch (result) {
                            case RecycleResult.Success s -> {
                                // The island is gone, so its marker must go with it. A web map that
                                // keeps drawing an island nobody can visit is worse than no map.
                                IslandMarkerSynchroniser markers = this.markerSynchroniser;
                                if (markers != null) {
                                    markers.onIslandRemoved(islandId);
                                }
                                if (antiAbuse != null && antiAbuse.purgeInventoryOnReset()) {
                                    com.uxplima.uxmskyblock.bukkit.antiabuse.ResetInventoryPurge.purgeAndSettle(
                                            antiAbuse, schedulerPort, player);
                                }
                                // Asynchronous, because Folia throws on a synchronous teleport, and
                                // that left the player standing where their island had been with no
                                // word that it was gone.
                                var unusedTeleport =
                                        player.teleportAsync(player.getWorld().getSpawnLocation());
                                send(player, "reset.success");
                                send(player, "reset.success_hint");
                            }
                            case RecycleResult.NotOwner no -> send(player, "reset.not_owner");
                            case RecycleResult.InvalidChallenge ic -> send(player, "reset.invalid_challenge");
                            case RecycleResult.IslandNotFound nf -> send(player, "reset.island_not_found");
                            case RecycleResult.AlreadyRunning ar -> send(player, "reset.already_running");
                            case RecycleResult.Failure f -> {
                                LOGGER.warning(() -> "Resetting island " + islandId + " for " + player.getName()
                                        + " failed: " + f.reason());
                                send(player, "reset.failed");
                            }
                        }
                    });
                });
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
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = findIslandId(player);
            if (optIslandId.isEmpty()) {
                send(player, "name.requires_island");
                return;
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
        });
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
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            send(player, "error.session_not_active");
            return Cmd.OK;
        }
        ProfileId profileId = optProfile.get();
        String rawName = StringArgumentType.getString(ctx, "name");

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = findIslandId(player);
            if (optIslandId.isEmpty()) {
                send(player, "name.requires_island");
                return;
            }
            try {
                IslandName newName = nameService.renameIsland(optIslandId.get(), profileId, rawName);
                IslandMarkerSynchroniser markers = this.markerSynchroniser;
                if (markers != null) {
                    markers.onIslandChanged(optIslandId.get());
                }
                send(player, "name.renamed", Placeholder.unparsed("name", newName.value()));
            } catch (IslandNameRefusedException refused) {
                switch (refused.reason()) {
                    case LENGTH ->
                        send(
                                player,
                                "name.refused_length",
                                Placeholder.unparsed("min", Integer.toString(IslandName.MIN_LENGTH)),
                                Placeholder.unparsed("max", Integer.toString(IslandName.MAX_LENGTH)));
                    case CHARACTERS -> send(player, "name.refused_characters");
                    case RESERVED -> send(player, "name.refused_reserved");
                    case UNSAFE -> send(player, "name.refused_unsafe");
                }
            } catch (SecurityException e) {
                send(player, "name.refused_permission");
            } catch (IllegalStateException e) {
                send(player, "name.refused_taken");
            } catch (IllegalArgumentException e) {
                // The island went away between finding it and renaming it.
                send(player, "name.requires_island");
            }
        });
        return Cmd.OK;
    }

    /** The preset ids an operator can actually pass, read off the catalogue rather than typed. */
    private String availablePresetIds() {
        return presetCatalog.allPresets().stream()
                .map(preset -> preset.id())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Whether this player is let past the reset cooldown and the daily limit.
     *
     * <p>The node comes off the operator's file. A node with no file behind it falls back to the
     * one this plugin ships with, which is the same name the file is written with.
     */
    private boolean mayBypassTheResetRules(Player player) {
        com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration rules = this.antiAbuseRules;
        String node = rules == null
                ? com.uxplima.uxmskyblock.bukkit.config.AntiAbuseConfiguration.DEFAULT_RESET_BYPASS_PERMISSION
                : rules.resetBypassPermission();
        return player.hasPermission(node) || player.isOp();
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
            return String.format(Locale.ROOT, "%dh %dm %ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%ds", secs);
    }
}
