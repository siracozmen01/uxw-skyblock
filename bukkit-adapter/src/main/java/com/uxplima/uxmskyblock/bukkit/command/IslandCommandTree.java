package com.uxplima.uxmskyblock.bukkit.command;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.biome.BiomeModificationPort;
import com.uxplima.uxmskyblock.core.application.island.IslandAuthorityPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.leaderboard.IslandLeaderboardPort;
import com.uxplima.uxmskyblock.core.application.preset.StarterPresetCatalog;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.biome.IslandBiome;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardCategory;
import com.uxplima.uxmskyblock.core.domain.leaderboard.LeaderboardEntry;
import com.uxplima.uxmskyblock.core.domain.preset.StarterPreset;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.world.IslandCoordinates;
import com.uxplima.uxmskyblock.core.domain.world.SpiralGridCoordinateAllocator;

/**
 * Paper Brigadier command tree for {@code /island} and {@code /is}.
 */
public final class IslandCommandTree {

    private final IslandStoragePort islandStoragePort;
    private final IslandAuthorityPort islandAuthorityPort;
    private final IslandBankPort islandBankPort;
    private final IslandUpgradeStoragePort islandUpgradePort;
    private final IslandLeaderboardPort islandLeaderboardPort;
    private final BiomeModificationPort biomeModificationPort;
    private final StarterPresetCatalog presetCatalog;
    private final StarterSchematicEngine schematicEngine;
    private final SpiralGridCoordinateAllocator coordinateAllocator;
    private final IslandProtectionListener protectionListener;
    private final String worldName;
    private final AtomicLong nextIslandIndex = new AtomicLong(1);

    public IslandCommandTree(
            IslandStoragePort islandStoragePort,
            IslandAuthorityPort islandAuthorityPort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort islandUpgradePort,
            IslandLeaderboardPort islandLeaderboardPort,
            BiomeModificationPort biomeModificationPort,
            StarterPresetCatalog presetCatalog,
            StarterSchematicEngine schematicEngine,
            SpiralGridCoordinateAllocator coordinateAllocator,
            IslandProtectionListener protectionListener,
            String worldName) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort");
        this.islandAuthorityPort = Objects.requireNonNull(islandAuthorityPort, "islandAuthorityPort");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort");
        this.islandUpgradePort = Objects.requireNonNull(islandUpgradePort, "islandUpgradePort");
        this.islandLeaderboardPort = Objects.requireNonNull(islandLeaderboardPort, "islandLeaderboardPort");
        this.biomeModificationPort = Objects.requireNonNull(biomeModificationPort, "biomeModificationPort");
        this.presetCatalog = Objects.requireNonNull(presetCatalog, "presetCatalog");
        this.schematicEngine = Objects.requireNonNull(schematicEngine, "schematicEngine");
        this.coordinateAllocator = Objects.requireNonNull(coordinateAllocator, "coordinateAllocator");
        this.protectionListener = Objects.requireNonNull(protectionListener, "protectionListener");
        this.worldName = Objects.requireNonNull(worldName, "worldName");
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

        if (islandStoragePort.findIslandIdByProfileId(profileId).isPresent()) {
            send(
                    player,
                    Component.text(
                            "You already own or belong to an island! Use /is home to visit it.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<StarterPreset> optPreset = presetCatalog.findById(presetId);
        if (optPreset.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "Unknown preset '" + presetId + "'. Available: classic, desert, nether, cave.",
                            NamedTextColor.RED));
            return Cmd.OK;
        }

        StarterPreset preset = optPreset.get();
        IslandId islandId = IslandId.of(UUID.randomUUID());

        long idx = nextIslandIndex.getAndIncrement();
        IslandCoordinates coords = coordinateAllocator.coordinatesForIndex(idx);
        int centerX = coords.x();
        int centerZ = coords.z();
        int initialRadius = 50;

        IslandBounds bounds = IslandBounds.fromCenterAndRadius(centerX, centerZ, initialRadius);
        Island island = Island.create(islandId, bounds, playerUuid, profileId, Instant.now());

        World world = Bukkit.getWorld(worldName);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }

        int spawnY = 100;
        IslandLocation location = new IslandLocation(
                islandId,
                world != null ? world.getName() : worldName,
                bounds,
                centerX + 0.5,
                spawnY + 1.0,
                centerZ + 0.5,
                0.0f,
                0.0f);

        // Save island, acquire authority & initial bank
        islandStoragePort.saveIsland(island, location);
        islandAuthorityPort.acquireAuthority(islandId, ServerNodeId.of("local-node"), 86400);
        islandBankPort.createBank(islandId);
        protectionListener.cacheIsland(island);

        // Paste structure if world is available
        if (world != null) {
            schematicEngine.pastePreset(world, centerX, spawnY, centerZ, preset);
            player.teleport(new Location(world, centerX + 0.5, spawnY + 1.0, centerZ + 0.5, 0.0f, 0.0f));
        }

        send(
                player,
                Component.text(
                        "Island created successfully with preset '" + preset.displayName() + "'!",
                        NamedTextColor.GREEN));
        return Cmd.OK;
    }

    private int executeHome(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(
                    ctx.getSource().getSender(),
                    Component.text("Only players can teleport to an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = new ProfileId(player.getUniqueId());
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            send(
                    player,
                    Component.text(
                            "You do not have an island yet! Use /is create to get started.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<IslandLocation> optLoc = islandStoragePort.findLocationByIslandId(optIslandId.get());
        if (optLoc.isEmpty()) {
            send(player, Component.text("Island location could not be resolved.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandLocation loc = optLoc.get();
        World world = Bukkit.getWorld(loc.worldName());
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }

        if (world != null) {
            player.teleport(
                    new Location(world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch()));
            send(player, Component.text("Welcome to your island!", NamedTextColor.GREEN));
        } else {
            send(player, Component.text("Island world is currently unloaded.", NamedTextColor.RED));
        }
        return Cmd.OK;
    }

    private int executeSetSpawn(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            send(ctx.getSource().getSender(), Component.text("Only players can set spawn.", NamedTextColor.RED));
            return Cmd.OK;
        }

        ProfileId profileId = new ProfileId(player.getUniqueId());
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You do not have an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Optional<Island> optIsland = islandStoragePort.findIslandById(optIslandId.get());
        Optional<IslandLocation> optLoc = islandStoragePort.findLocationByIslandId(optIslandId.get());
        if (optIsland.isEmpty() || optLoc.isEmpty()) {
            send(player, Component.text("Island data not found.", NamedTextColor.RED));
            return Cmd.OK;
        }

        Location current = player.getLocation();
        IslandLocation newLoc = new IslandLocation(
                optIslandId.get(),
                current.getWorld().getName(),
                optLoc.get().bounds(),
                current.getX(),
                current.getY(),
                current.getZ(),
                current.getYaw(),
                current.getPitch());

        islandStoragePort.saveIsland(optIsland.get(), newLoc);
        send(player, Component.text("Island spawn location updated.", NamedTextColor.GREEN));
        return Cmd.OK;
    }

    private int executeBankBalance(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        ProfileId profileId = new ProfileId(player.getUniqueId());
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You do not have an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        var bank = islandBankPort.findBankByIslandId(optIslandId.get());
        if (bank.isEmpty()) {
            send(player, Component.text("Bank account not found.", NamedTextColor.RED));
            return Cmd.OK;
        }

        send(
                player,
                Component.text(
                        "Island Bank Balance: $" + (bank.get().primaryBalanceMinorUnits() / 100.0),
                        NamedTextColor.GREEN));
        return Cmd.OK;
    }

    private int executeBankDeposit(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        ProfileId profileId = new ProfileId(player.getUniqueId());
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You do not have an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIslandId.get();
        Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(islandId);
        IslandBank bank = optBank.orElseGet(() -> islandBankPort.createBank(islandId));

        long minorUnits = amount * 100;
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = "cmd-deposit-" + operationId;

        BankTransactionOutcome outcome = islandBankPort.executeTransaction(
                islandId,
                player.getUniqueId(),
                "PRIMARY",
                2,
                minorUnits,
                "Player deposit",
                "local-node",
                1L,
                bank.version(),
                operationId,
                idempotencyKey);

        if (outcome instanceof BankTransactionOutcome.Success) {
            send(player, Component.text("Deposited $" + amount + " into the island bank.", NamedTextColor.GREEN));
        } else {
            send(player, Component.text("Deposit failed: " + outcome, NamedTextColor.RED));
        }
        return Cmd.OK;
    }

    private int executeBankWithdraw(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getSender() instanceof Player player)) {
            return Cmd.OK;
        }
        long amount = LongArgumentType.getLong(ctx, "amount");
        ProfileId profileId = new ProfileId(player.getUniqueId());
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You do not have an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandId islandId = optIslandId.get();
        Optional<IslandBank> optBank = islandBankPort.findBankByIslandId(islandId);
        if (optBank.isEmpty()) {
            send(player, Component.text("Bank account not found.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandBank bank = optBank.get();
        long minorUnits = amount * 100;
        UUID operationId = UUID.randomUUID();
        String idempotencyKey = "cmd-withdraw-" + operationId;

        BankTransactionOutcome outcome = islandBankPort.executeTransaction(
                islandId,
                player.getUniqueId(),
                "PRIMARY",
                2,
                -minorUnits,
                "Player withdrawal",
                "local-node",
                1L,
                bank.version(),
                operationId,
                idempotencyKey);

        if (outcome instanceof BankTransactionOutcome.Success) {
            send(player, Component.text("Withdrew $" + amount + " from the island bank.", NamedTextColor.GREEN));
        } else if (outcome instanceof BankTransactionOutcome.InsufficientFunds) {
            send(player, Component.text("Insufficient funds in the island bank.", NamedTextColor.RED));
        } else {
            send(player, Component.text("Withdrawal failed: " + outcome, NamedTextColor.RED));
        }
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

        ProfileId profileId = new ProfileId(player.getUniqueId());
        Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
        if (optIslandId.isEmpty()) {
            send(player, Component.text("You do not have an island.", NamedTextColor.RED));
            return Cmd.OK;
        }

        IslandBiome targetBiome = optBiome.get();
        var unused = biomeModificationPort
                .applyBiome(optIslandId.get(), targetBiome)
                .thenAccept(success -> {
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

        var entries = islandLeaderboardPort.fetchTopIslands(cat, 10);
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
        return Cmd.OK;
    }
}
