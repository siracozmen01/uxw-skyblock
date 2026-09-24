package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
    private final @Nullable BedrockFormService bedrockFormService;
    private volatile @Nullable SkyblockMenuEngine menuEngine;

    private volatile @Nullable ToIntFunction<IslandId> vaultPages;
    private volatile @Nullable UnaryOperator<Map<UpgradeId, Integer>> upgradeStanding;
    private final Messages messages;

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            @Nullable BedrockFormService bedrockFormService,
            Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.islandBankPort = Objects.requireNonNull(islandBankPort, "islandBankPort must not be null");
        this.upgradeStoragePort = Objects.requireNonNull(upgradeStoragePort, "upgradeStoragePort must not be null");
        this.locationService = Objects.requireNonNull(locationService, "locationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.worldName = Objects.requireNonNull(worldName, "worldName must not be null");
        this.activeProfileProvider =
                Objects.requireNonNull(activeProfileProvider, "activeProfileProvider must not be null");
        this.bedrockFormService = bedrockFormService;
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Function<UUID, Optional<ProfileId>> activeProfileProvider,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                activeProfileProvider,
                null,
                messages);
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable BedrockFormService bedrockFormService,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                sessionCoordinator != null ? sessionCoordinator::activeProfile : uuid -> Optional.empty(),
                bedrockFormService,
                messages);
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                sessionCoordinator,
                null,
                messages);
    }

    public IslandControlMenu(
            IslandStoragePort islandStoragePort,
            IslandBankPort islandBankPort,
            IslandUpgradeStoragePort upgradeStoragePort,
            IslandLocationService locationService,
            SchedulerPort schedulerPort,
            String worldName,
            Messages messages) {
        this(
                islandStoragePort,
                islandBankPort,
                upgradeStoragePort,
                locationService,
                schedulerPort,
                worldName,
                (PlayerSessionCoordinator) null,
                messages);
    }

    /**
     * Hands this menu the engine that reads {@code menus/island-main.conf}.
     *
     * <p>When the operator's file is there, the file decides the layout. The Java built window below
     * stays as the answer to a file that is missing or will not parse, so a typo in a menu file
     * leaves a player with a working menu rather than with nothing.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine menuEngine) {
        this.menuEngine = menuEngine;
    }

    /**
     * How many vault pages an island has, read where the other values are read, off the player's
     * thread.
     *
     * <p>The vault tile showed the upgrade tier as the pages unlocked, so an island with its one base
     * page read "Pages unlocked: 0".
     */
    public void useVaultPages(@Nullable ToIntFunction<IslandId> vaultPages) {
        this.vaultPages = vaultPages;
    }

    /**
     * The tier each upgrade stands on, given what the island bought, so a free base tier reads as
     * held here the way the upgrade list reads it.
     */
    public void useUpgradeStanding(@Nullable UnaryOperator<Map<UpgradeId, Integer>> standing) {
        this.upgradeStanding = standing;
    }

    /**
     * The live values {@code island-main.conf} may spell as {@code %argument_<name>%}.
     *
     * <p>Every token here is one the server can actually answer. The file that shipped before spelled
     * {@code {bank_level}} and {@code {max_members}}, and neither existed anywhere in the code: it was
     * a dead placeholder in a file nothing read.
     */
    private Map<String, String> liveValues(
            Island island, @Nullable IslandBank bank, @Nullable Map<UpgradeId, Integer> upgrades, int vaultPages) {
        long minorBalance = bank != null ? bank.primaryBalanceMinorUnits() : 0L;
        Map<UpgradeId, Integer> tiers = upgrades != null ? upgrades : Map.of();
        IslandBounds bounds = island.bounds();
        return Map.ofEntries(
                Map.entry("island", island.id().value().toString()),
                Map.entry("balance", String.format(Locale.US, "%.2f", (double) minorBalance / 100.0)),
                Map.entry("crystals", String.valueOf(bank != null ? bank.crystalsBalance() : 0L)),
                Map.entry("exp", String.valueOf(bank != null ? bank.expBalance() : 0L)),
                Map.entry("member_count", String.valueOf(island.members().size())),
                Map.entry("radius", String.valueOf(bounds.radius())),
                Map.entry("center_x", String.valueOf(bounds.centerX())),
                Map.entry("center_z", String.valueOf(bounds.centerZ())),
                Map.entry("size_tier", String.valueOf(tiers.getOrDefault(UpgradeId.SIZE, 0))),
                Map.entry("members_tier", String.valueOf(tiers.getOrDefault(UpgradeId.MEMBERS, 0))),
                Map.entry("warps_tier", String.valueOf(tiers.getOrDefault(UpgradeId.WARPS, 0))),
                Map.entry("spawner_tier", String.valueOf(tiers.getOrDefault(UpgradeId.SPAWNER_RATES, 0))),
                Map.entry("generator_tier", String.valueOf(tiers.getOrDefault(UpgradeId.ORE_GENERATOR, 0))),
                Map.entry("vault_tier", String.valueOf(tiers.getOrDefault(UpgradeId.VAULT_PAGES, 0))),
                Map.entry("vault_pages", String.valueOf(vaultPages)),
                Map.entry("crop_tier", String.valueOf(tiers.getOrDefault(UpgradeId.CROP_GROWTH, 0))));
    }

    public void open(Player player) {
        Objects.requireNonNull(player, "player must not be null");
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        Optional<ProfileId> activeOpt = activeProfileProvider.apply(player.getUniqueId());
        if (activeOpt.isEmpty()) {
            schedulerPort.onEntity(playerUuid, () -> {
                if (player.isOnline()) {
                    player.sendMessage(messages.render(player, "error.session_not_active"));
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
                        player.sendMessage(messages.render(player, "menu.control.no_island"));
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
            Map<UpgradeId, Integer> bought = upgradeStoragePort.getUpgrades(islandId);
            UnaryOperator<Map<UpgradeId, Integer>> standingOf = this.upgradeStanding;
            Map<UpgradeId, Integer> upgrades = standingOf == null ? bought : standingOf.apply(bought);
            ToIntFunction<IslandId> pagesOf = this.vaultPages;
            int pages = pagesOf == null ? 1 : pagesOf.applyAsInt(islandId);
            Optional<IslandLocation> optLoc = locationService.resolveHome(profileId);

            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (bedrockFormService != null && bedrockFormService.isBedrock(player)) {
                    bedrockFormService.openIslandControlForm(
                            player,
                            island,
                            () -> {
                                if (optLoc.isPresent()) {
                                    IslandLocation loc = optLoc.get();
                                    World world = Bukkit.getWorld(worldName);
                                    if (world != null) {
                                        Location target = new Location(
                                                world,
                                                loc.spawnX(),
                                                loc.spawnY(),
                                                loc.spawnZ(),
                                                loc.spawnYaw(),
                                                loc.spawnPitch());
                                        var unused = player.teleportAsync(target);
                                        player.sendMessage(messages.render(player, "menu.control.home_success"));
                                    } else {
                                        player.sendMessage(messages.render(player, "menu.control.home_world_unloaded"));
                                    }
                                } else {
                                    player.sendMessage(messages.render(player, "menu.control.home_missing"));
                                }
                            },
                            () -> messages.send(player, "menu.control.warps_hint"),
                            () -> messages.send(player, "menu.control.bank_hint"),
                            () -> messages.send(player, "menu.control.members_hint"),
                            () -> messages.send(player, "menu.control.settings_hint"));
                    return;
                }
                SkyblockMenuEngine engine = this.menuEngine;
                if (engine != null && engine.open(player, "island-main", liveValues(island, bank, upgrades, pages))) {
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
            @Nullable IslandBank bank,
            Map<UpgradeId, Integer> upgrades,
            Optional<IslandLocation> optLoc) {

        Component title = messages.renderPlain(player, "menu.control.title");
        SimpleGui gui = Guis.gui().title(title).rows(4).build();

        // Fill border
        ItemStack border = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.space())
                .build();
        gui.filler().fillBorder(GuiItem.display(border));

        // Slot 10: Overview
        IslandBounds bounds = island.bounds();
        ItemStack overviewItem = ItemBuilder.of(Material.GRASS_BLOCK)
                .name(messages.renderPlain(player, "menu.control.overview_name"))
                .lore(messages.renderAll(
                        player,
                        "menu.control.overview_lore",
                        Placeholder.unparsed(
                                "owner",
                                island.ownerPlayerUuid().value().toString().substring(0, 8)),
                        Placeholder.unparsed(
                                "members", String.valueOf(island.members().size())),
                        Placeholder.unparsed("x", String.valueOf(bounds.centerX())),
                        Placeholder.unparsed("z", String.valueOf(bounds.centerZ())),
                        Placeholder.unparsed("radius", String.valueOf(bounds.radius()))))
                .build();
        gui.set(10, GuiItem.display(overviewItem));

        // Slot 11: Bank
        long minorBalance = (bank != null) ? bank.primaryBalanceMinorUnits() : 0L;
        long crystals = (bank != null) ? bank.crystalsBalance() : 0L;
        ItemStack bankItem = ItemBuilder.of(Material.GOLD_INGOT)
                .name(messages.renderPlain(player, "menu.control.bank_name"))
                .lore(messages.renderAll(
                        player,
                        "menu.control.bank_lore",
                        Placeholder.unparsed(
                                "balance", String.format(Locale.US, "%.2f", (double) minorBalance / 100.0)),
                        Placeholder.unparsed("crystals", String.valueOf(crystals))))
                .build();
        gui.set(11, GuiItem.button(bankItem, e -> {
            player.closeInventory();
            messages.send(player, "menu.control.bank_hint");
        }));

        // Slot 12: Upgrades
        int sizeTier = (upgrades != null) ? upgrades.getOrDefault(UpgradeId.SIZE, 0) : 0;
        int spawnerTier = (upgrades != null) ? upgrades.getOrDefault(UpgradeId.SPAWNER_RATES, 0) : 0;
        ItemStack upgradeItem = ItemBuilder.of(Material.NETHER_STAR)
                .name(messages.renderPlain(player, "menu.control.upgrades_name"))
                .lore(messages.renderAll(
                        player,
                        "menu.control.upgrades_lore",
                        Placeholder.unparsed("size", String.valueOf(sizeTier)),
                        Placeholder.unparsed("spawner", String.valueOf(spawnerTier))))
                .build();
        gui.set(12, GuiItem.button(upgradeItem, e -> {
            player.closeInventory();
            messages.send(player, "menu.control.upgrades_hint");
        }));

        // Slot 13: Biome
        ItemStack biomeItem = ItemBuilder.of(Material.OAK_SAPLING)
                .name(messages.renderPlain(player, "menu.control.biome_name"))
                .lore(messages.renderAll(player, "menu.control.biome_lore"))
                .build();
        gui.set(13, GuiItem.button(biomeItem, e -> {
            player.closeInventory();
            messages.send(player, "menu.control.biome_hint");
        }));

        // Slot 14: Members
        ItemStack membersItem = ItemBuilder.of(Material.PLAYER_HEAD)
                .name(messages.renderPlain(player, "menu.control.members_name"))
                .lore(messages.renderAll(
                        player,
                        "menu.control.members_lore",
                        Placeholder.unparsed(
                                "members", String.valueOf(island.members().size()))))
                .build();
        gui.set(14, GuiItem.button(membersItem, e -> {
            player.closeInventory();
            messages.send(player, "menu.control.members_hint");
        }));

        // Slot 15: Flags & Settings
        ItemStack settingsItem = ItemBuilder.of(Material.REDSTONE_TORCH)
                .name(messages.renderPlain(player, "menu.control.settings_name"))
                .lore(messages.renderAll(player, "menu.control.settings_lore"))
                .build();
        gui.set(15, GuiItem.button(settingsItem, e -> {
            player.closeInventory();
            messages.send(player, "menu.control.settings_hint");
        }));

        // Slot 16: Teleport Home
        ItemStack homeItem = ItemBuilder.of(Material.COMPASS)
                .name(messages.renderPlain(player, "menu.control.home_name"))
                .lore(messages.renderAll(player, "menu.control.home_lore"))
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
                    messages.send(player, "menu.control.home_success");
                } else {
                    messages.send(player, "menu.control.home_world_unloaded");
                }
            } else {
                messages.send(player, "menu.control.home_missing");
            }
        }));

        // Slot 31: Close Menu
        ItemStack closeItem = ItemBuilder.of(Material.BARRIER)
                .name(messages.renderPlain(player, "menu.control.close"))
                .build();
        gui.set(31, GuiItem.button(closeItem, e -> player.closeInventory()));

        return gui;
    }
}
