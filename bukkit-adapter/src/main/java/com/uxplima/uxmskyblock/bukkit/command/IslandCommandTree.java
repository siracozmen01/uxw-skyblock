package com.uxplima.uxmskyblock.bukkit.command;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.command.brigadier.CommandSourceStack;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.uxplima.uxmlib.command.Cmd;
import com.uxplima.uxmlib.command.CommandRegistrar;
import com.uxplima.uxmskyblock.bukkit.listener.IslandProtectionListener;
import com.uxplima.uxmskyblock.bukkit.schematic.StarterSchematicEngine;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.CreateIslandUseCase;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardService;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;

/**
 * Paper Brigadier command tree for {@code /island} and {@code /is}.
 *
 * <p>All storage, persistence, and distributed authority I/O is dispatched asynchronously off tick threads
 * via {@link SchedulerPort#async(Runnable)}. Platform mutations, player teleports, inventory updates, and
 * player feedback are strictly scheduled onto the player's owning Folia {@link org.bukkit.entity.Entity} region
 * thread via {@link SchedulerPort#onEntity(PlayerUuid, Runnable)}.
 */
public final class IslandCommandTree {

    private final CreateIslandUseCase createIslandUseCase;
    private final IslandLocationService islandLocationService;
    private final IslandBankService islandBankService;
    private final IslandUpgradeStoragePort islandUpgradePort;
    private final IslandLeaderboardService islandLeaderboardService;
    private final BiomeModificationPort biomeModificationPort;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final IslandProtectionListener protectionListener;
    private final SchedulerPort schedulerPort;
    private final ServerNodeId serverNodeId;
    private final String worldName;

    public IslandCommandTree(
            CreateIslandUseCase createIslandUseCase,
            IslandLocationService islandLocationService,
            IslandBankService islandBankService,
            IslandUpgradeStoragePort islandUpgradePort,
            IslandLeaderboardService islandLeaderboardService,
            BiomeModificationPort biomeModificationPort,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            IslandProtectionListener protectionListener,
            SchedulerPort schedulerPort,
            ServerNodeId serverNodeId,
            String worldName) {
        this.createIslandUseCase = Objects.requireNonNull(createIslandUseCase, "createIslandUseCase must not be null");
        this.islandLocationService =
                Objects.requireNonNull(islandLocationService, "islandLocationService must not be null");
        this.islandBankService = Objects.requireNonNull(islandBankService, "islandBankService must not be null");
        this.islandUpgradePort = Objects.requireNonNull(islandUpgradePort, "islandUpgradePort must not be null");
        this.islandLeaderboardService =
                Objects.requireNonNull(islandLeaderboardService, "islandLeaderboardService must not be null");
        this.biomeModificationPort =
                Objects.requireNonNull(biomeModificationPort, "biomeModificationPort must not be null");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog must not be null");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine must not be null");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.serverNodeId = Objects.requireNonNull(serverNodeId, "serverNodeId must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
    }

    public IslandUpgradeStoragePort islandUpgradePort() {
        return islandUpgradePort;
    }

    public void register(JavaPlugin plugin) {
        LiteralArgumentBuilder<CommandSourceStack> root = Cmd.literal("island")
                .executes(this::executeHelp)
                .then(Cmd.literal("help").executes(this::executeHelp))
                .then(Cmd.literal("create")
                        .executes(ctx ->
                                executeCreate(ctx, presetCatalog.defaultPreset().id()))
                        .then(Cmd.argument("preset", StringArgumentType.word())
                                .executes(ctx -> executeCreate(ctx, StringArgumentType.getString(ctx, "preset")))))
                .then(Cmd.literal("home").executes(this::executeHome))
                .then(Cmd.literal("go").executes(this::executeHome))
                .then(Cmd.literal("setspawn").executes(this::executeSetSpawn))
                .then(Cmd.literal("bank")
                        .executes(this::executeBankBalance)
                        .then(Cmd.literal("balance").executes(this::executeBankBalance))
                        .then(Cmd.literal("deposit")
                                .then(Cmd.argument("amount", LongArgumentType.longArg(1))
                                        .executes(this::executeBankDeposit)))
                        .then(Cmd.literal("withdraw")
                                .then(Cmd.argument("amount", LongArgumentType.longArg(1))
                                        .executes(this::executeBankWithdraw))))
                .then(Cmd.literal("biome")
                        .then(Cmd.argument("type", StringArgumentType.word()).executes(this::executeBiomeChange)))
                .then(Cmd.literal("top")
                        .executes(ctx -> executeTop(ctx, "level"))
                        .then(Cmd.argument("category", StringArgumentType.word())
                                .executes(ctx -> executeTop(ctx, StringArgumentType.getString(ctx, "category")))));

        CommandRegistrar.register(plugin, root, "Main Skyblock command tree", "is");
    }

    private static void send(Audience audience, Component component) {
        audience.sendMessage(component);
    }

    private int executeHelp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        send(src.getSender(), Component.text("--- UXPLIMA Skyblock Commands ---", NamedTextColor.GOLD));
        send(src.getSender(), Component.text("/is create [preset] - Create your island", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is home - Teleport to your island", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is setspawn - Set your island spawn", NamedTextColor.YELLOW));
        send(
                src.getSender(),
                Component.text("/is bank [deposit|withdraw|balance] - Manage island bank", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is biome <type> - Change island biome", NamedTextColor.YELLOW));
        send(src.getSender(), Component.text("/is top [level|worth|bank] - View leaderboards", NamedTextColor.YELLOW));
        return Cmd.OK;
    }

    private int executeCreate(CommandContext<CommandSourceStack> ctx, String presetId) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can create an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());

        schedulerPort.async(() -> {
            CreateIslandUseCase.CreateIslandResult result =
                    createIslandUseCase.execute(playerUuid, profileId, presetId, serverNodeId, worldName);

            schedulerPort.onEntity(playerUuid, () -> {
                if (result instanceof CreateIslandUseCase.CreateIslandResult.Success success) {
                    protectionListener.cacheIsland(success.island());

                    World world = Bukkit.getWorld(worldName);
                    if (world == null && !Bukkit.getWorlds().isEmpty()) {
                        world = Bukkit.getWorlds().get(0);
                    }
                    if (world != null) {
                        int centerX = success.location().bounds().centerX();
                        int centerZ = success.location().bounds().centerZ();
                        int spawnY = 100;
                        schematicEngine.pastePreset(world, centerX, spawnY, centerZ, success.preset());
                        player.teleport(new Location(
                                world,
                                success.location().spawnX(),
                                success.location().spawnY(),
                                success.location().spawnZ(),
                                0.0f,
                                0.0f));
                    }
                    send(
                            player,
                            Component.text(
                                    "Island created successfully with preset '"
                                            + success.preset().displayName() + "'!",
                                    NamedTextColor.GREEN));
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

    private int executeHome(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can teleport to an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<IslandLocation> optLoc = islandLocationService.resolveHome(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optLoc.isEmpty()) {
                    send(
                            player,
                            Component.text(
                                    "You do not have an island yet! Use /is create to get started.",
                                    NamedTextColor.RED));
                    return;
                }
                IslandLocation loc = optLoc.get();
                World world = Bukkit.getWorld(loc.worldName());
                if (world == null && !Bukkit.getWorlds().isEmpty()) {
                    world = Bukkit.getWorlds().get(0);
                }
                if (world != null) {
                    player.teleport(new Location(
                            world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch()));
                    send(player, Component.text("Welcome to your island!", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("Island world is currently unloaded.", NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeSetSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can set spawn.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());
        Location current = player.getLocation();
        String currentWorld = current.getWorld() != null ? current.getWorld().getName() : this.worldName;
        double x = current.getX();
        double y = current.getY();
        double z = current.getZ();
        float yaw = current.getYaw();
        float pitch = current.getPitch();

        schedulerPort.async(() -> {
            boolean updated = islandLocationService.updateSpawn(profileId, currentWorld, x, y, z, yaw, pitch);
            schedulerPort.onEntity(playerUuid, () -> {
                if (updated) {
                    send(player, Component.text("Island spawn location updated.", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("You do not have an island.", NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankBalance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());

        schedulerPort.async(() -> {
            Optional<Long> optBalance = islandBankService.getBalanceMinorUnits(profileId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (optBalance.isEmpty()) {
                    send(player, Component.text("You do not have an island.", NamedTextColor.RED));
                } else {
                    send(
                            player,
                            Component.text(
                                    "Island Bank Balance: $" + (optBalance.get() / 100.0), NamedTextColor.GREEN));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankDeposit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());
        long minorUnits = amount * 100;

        schedulerPort.async(() -> {
            BankTransactionOutcome outcome = islandBankService.deposit(profileId, playerUuid, minorUnits, serverNodeId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    send(
                            player,
                            Component.text("Deposited $" + amount + " into the island bank.", NamedTextColor.GREEN));
                } else {
                    send(player, Component.text("Deposit failed: " + outcome, NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());
        long minorUnits = amount * 100;

        schedulerPort.async(() -> {
            BankTransactionOutcome outcome =
                    islandBankService.withdraw(profileId, playerUuid, minorUnits, serverNodeId);
            schedulerPort.onEntity(playerUuid, () -> {
                if (outcome instanceof BankTransactionOutcome.Success) {
                    send(
                            player,
                            Component.text("Withdrew $" + amount + " from the island bank.", NamedTextColor.GREEN));
                } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
                    send(player, Component.text("Insufficient funds in the island bank.", NamedTextColor.RED));
                } else {
                    send(player, Component.text("Withdrawal failed: " + outcome, NamedTextColor.RED));
                }
            });
        });

        return Cmd.OK;
    }

    private int executeBiomeChange(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        String biomeName = StringArgumentType.getString(ctx, "type");
        Optional<IslandBiome> optBiome = IslandBiome.fromId(biomeName);
        if (optBiome.isEmpty()) {
            send(player, Component.text("Unknown biome '" + biomeName + "'.", NamedTextColor.RED));
            return Cmd.OK;
        }

        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        ProfileId profileId = new ProfileId(player.getUniqueId());
        IslandBiome targetBiome = optBiome.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandLocationService.findIslandId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(
                        playerUuid,
                        () -> send(player, Component.text("You do not have an island.", NamedTextColor.RED)));
                return;
            }

            var unused = biomeModificationPort
                    .applyBiome(optIslandId.get(), targetBiome)
                    .thenAccept(success -> {
                        schedulerPort.onEntity(playerUuid, () -> {
                            if (success) {
                                send(
                                        player,
                                        Component.text(
                                                "Island biome changed to " + targetBiome.displayName() + "!",
                                                NamedTextColor.GREEN));
                            } else {
                                send(player, Component.text("Failed to update island biome.", NamedTextColor.RED));
                            }
                        });
                    });
        });

        return Cmd.OK;
    }

    private int executeTop(CommandContext<CommandSourceStack> ctx, String category) {
        CommandSourceStack src = ctx.getSource();
        LeaderboardCategory cat =
                switch (category.toLowerCase(Locale.ROOT)) {
                    case "worth" -> LeaderboardCategory.WORTH;
                    case "bank" -> LeaderboardCategory.BANK;
                    default -> LeaderboardCategory.LEVEL;
                };

        schedulerPort.async(() -> {
            var entries = islandLeaderboardService.getTop(cat, 10);
            schedulerPort.onGlobal(() -> {
                send(src.getSender(), Component.text("--- Top Islands (" + cat.name() + ") ---", NamedTextColor.GOLD));
                if (entries.isEmpty()) {
                    send(src.getSender(), Component.text("No islands ranked yet.", NamedTextColor.GRAY));
                } else {
                    for (LeaderboardEntry entry : entries) {
                        String name = entry.islandName() != null
                                ? entry.islandName()
                                : entry.islandId().toString().substring(0, 8);
                        send(
                                src.getSender(),
                                Component.text(
                                        "#" + entry.rank() + " " + name + " - " + entry.formattedScore(),
                                        NamedTextColor.YELLOW));
                    }
                }
            });
        });

        return Cmd.OK;
    }
}
