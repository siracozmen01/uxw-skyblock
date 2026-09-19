package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankPort;
import com.uxplima.uxmskyblock.core.application.island.IslandLocationService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeStoragePort;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.island.IslandLocation;
import com.uxplima.uxmskyblock.core.domain.upgrade.UpgradeId;
import org.jspecify.annotations.Nullable;

/**
 * Interactive Chest GUI providing one-stop island management, bank stats, upgrades,
 * navigation, and permission control.
 */
public final class IslandControlMenu {

    private final IslandStoragePort islandStoragePort;
    private final IslandBankPort islandBankPort;
    private final IslandUpgradeStoragePort upgradeStoragePort;
    private final IslandLocationService locationService;
    private final SchedulerPort schedulerPort;
    private final String worldName;
    private final Function<UUID, Optional<ProfileId>> activeProfileProvider;

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Function<UUID, Optional<ProfileId>> activeProfileProvider) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.upgradeStoragePort = Objects.requireNonNull(upgradeStoragePort, "upgradeStoragePort must not be null");
        this.locationService = Objects.requireNonNull(locationService, "locationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.activeProfileProvider = Objects.requireNonNull(activeProfileProvider, "activeProfileProvider must not be null");
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty());
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName) {
        this(islandStoragePort, islandBankPort, upgradeStoragePort, locationService, schedulerPort, worldName, (PlayerSessionCoordinator) null);
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> activeOpt = activeProfileProvider.apply(player.getUniqueId());
        if (activeOpt.isEmpty()) {
            schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    player.sendMessage(Component.text(
                            "Your profile session is not active or still loading. Please wait.",
                            NamedTextColor.RED));
                }
            });
            return;
        }
        ProfileId profileId = activeOpt.get();

        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(
                                "You do not belong to an island. Use /is create to start one!", NamedTextColor.RED));
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            Optional<Island> optIsland = islandStoragePort.findIslandById(islandId);
            if (optIsland.isEmpty()) {
                return;
            }

            Island island = optIsland.get();
            IslandBank bank = islandBankPort.findBankByIslandId(islandId).orElse(null);
            Map<UpgradeId, Integer> upgrades = upgradeStoragePort.getUpgrades(islandId);
            Optional<IslandLocation> optLoc = locationService.resolveHome(profileId);

            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                SimpleGui gui = buildGui(player, island, bank, upgrades, optLoc);
                gui.open(player);
            });
        });
    }

    public SimpleGui buildGui(
            Player player,
            Island island,
            IslandBank bank,
            Map<UpgradeId, Integer> upgrades,
            Optional<IslandLocation> optLoc) {

        Component title = Component.text("Island Control Panel", NamedTextColor.DARK_AQUA, TextDecoration.BOLD);
        SimpleGui gui = Guis.gui().title(title).rows(4).build();

        // Fill border
        ItemStack border = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.text(" ", NamedTextColor.GRAY))
                .build();
        gui.filler().fillBorder(GuiItem.display(border));

        // Slot 10: Overview
        IslandBounds bounds = island.bounds();
        ItemStack overviewItem = ItemBuilder.of(Material.GRASS_BLOCK)
                .name(Component.text("Island Overview", NamedTextColor.GREEN, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Owner: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        island.ownerPlayerUuid()
                                                        .value()
                                                        .toString()
                                                        .substring(0, 8) + "...",
                                        NamedTextColor.WHITE)),
                        Component.text("Members: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        String.valueOf(island.members().size()), NamedTextColor.WHITE)),
                        Component.text("Center: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        bounds.centerX() + ", " + bounds.centerZ(), NamedTextColor.WHITE)),
                        Component.text("Radius: ", NamedTextColor.GRAY)
                                .append(Component.text(bounds.radius() + " blocks", NamedTextColor.WHITE))))
                .build();
        gui.set(10, GuiItem.display(overviewItem));

        // Slot 11: Bank
        long minorBalance = (bank != null) ? bank.primaryBalanceMinorUnits() : 0L;
        long crystals = (bank != null) ? bank.crystalsBalance() : 0L;
        ItemStack bankItem = ItemBuilder.of(Material.GOLD_INGOT)
                .name(Component.text("Island Bank", NamedTextColor.GOLD, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Balance: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        "$" + String.format(Locale.US, "%.2f", (double) minorBalance / 100.0),
                                        NamedTextColor.GREEN)),
                        Component.text("Crystals: ", NamedTextColor.GRAY)
                                .append(Component.text(String.valueOf(crystals), NamedTextColor.AQUA)),
                        Component.text("Click for bank commands", NamedTextColor.YELLOW)))
                .build();
        gui.set(11, GuiItem.button(bankItem, e -> {
            player.closeInventory();
            player.sendMessage(Component.text(
                    "Bank: Use /is bank deposit <amount> or /is bank withdraw <amount>", NamedTextColor.GOLD));
        }));

        // Slot 12: Upgrades
        int sizeTier = (upgrades != null) ? upgrades.getOrDefault(UpgradeId.SIZE, 0) : 0;
        int spawnerTier = (upgrades != null) ? upgrades.getOrDefault(UpgradeId.SPAWNER_SPEED, 0) : 0;
        ItemStack upgradeItem = ItemBuilder.of(Material.NETHER_STAR)
                .name(Component.text("Island Upgrades", NamedTextColor.AQUA, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Size Tier: ", NamedTextColor.GRAY)
                                .append(Component.text(String.valueOf(sizeTier), NamedTextColor.WHITE)),
                        Component.text("Spawner Speed: ", NamedTextColor.GRAY)
                                .append(Component.text(String.valueOf(spawnerTier), NamedTextColor.WHITE)),
                        Component.text("Click to view upgrades", NamedTextColor.YELLOW)))
                .build();
        gui.set(12, GuiItem.button(upgradeItem, e -> {
            player.closeInventory();
            player.sendMessage(Component.text(
                    "Upgrades: Use /is upgrade to manage your island progression.", NamedTextColor.AQUA));
        }));

        // Slot 13: Biome
        ItemStack biomeItem = ItemBuilder.of(Material.OAK_SAPLING)
                .name(Component.text("Island Biome", NamedTextColor.DARK_GREEN, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Customize your island environment.", NamedTextColor.GRAY),
                        Component.text("Click for biome command", NamedTextColor.YELLOW)))
                .build();
        gui.set(13, GuiItem.button(biomeItem, e -> {
            player.closeInventory();
            player.sendMessage(Component.text(
                    "Biome: Use /is biome <type> to update your island biome.", NamedTextColor.DARK_GREEN));
        }));

        // Slot 14: Members
        ItemStack membersItem = ItemBuilder.of(Material.PLAYER_HEAD)
                .name(Component.text("Island Members", NamedTextColor.YELLOW, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Total members: ", NamedTextColor.GRAY)
                                .append(Component.text(
                                        String.valueOf(island.members().size()), NamedTextColor.WHITE)),
                        Component.text("Click for member commands", NamedTextColor.YELLOW)))
                .build();
        gui.set(14, GuiItem.button(membersItem, e -> {
            player.closeInventory();
            player.sendMessage(
                    Component.text("Members: Use /is invite <player> or /is kick <player>", NamedTextColor.YELLOW));
        }));

        // Slot 15: Flags & Settings
        ItemStack settingsItem = ItemBuilder.of(Material.REDSTONE_TORCH)
                .name(Component.text("Island Settings", NamedTextColor.RED, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Configure island security and flags.", NamedTextColor.GRAY),
                        Component.text("Click for lock/unlock commands", NamedTextColor.YELLOW)))
                .build();
        gui.set(15, GuiItem.button(settingsItem, e -> {
            player.closeInventory();
            player.sendMessage(Component.text(
                    "Settings: Use /is lock or /is unlock to control visitor access.", NamedTextColor.RED));
        }));

        // Slot 16: Teleport Home
        ItemStack homeItem = ItemBuilder.of(Material.COMPASS)
                .name(Component.text("Teleport Home", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Warp instantly to island spawn.", NamedTextColor.GRAY),
                        Component.text("Click to teleport!", NamedTextColor.GREEN)))
                .build();
        gui.set(16, GuiItem.button(homeItem, e -> {
            player.closeInventory();
            if (optLoc.isPresent()) {
                IslandLocation loc = optLoc.get();
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Location target = new Location(
                            world, loc.spawnX(), loc.spawnY(), loc.spawnZ(), loc.spawnYaw(), loc.spawnPitch());
                    var unused = player.teleportAsync(target);
                    player.sendMessage(Component.text("Teleported to island home!", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Island world is unloaded.", NamedTextColor.RED));
                }
            } else {
                player.sendMessage(Component.text("Island home location not found.", NamedTextColor.RED));
            }
        }));

        // Slot 31: Close Menu
        ItemStack closeItem = ItemBuilder.of(Material.BARRIER)
                .name(Component.text("Close Menu", NamedTextColor.RED, TextDecoration.BOLD))
                .build();
        gui.set(31, GuiItem.button(closeItem, e -> player.closeInventory()));

        return gui;
    }
}
