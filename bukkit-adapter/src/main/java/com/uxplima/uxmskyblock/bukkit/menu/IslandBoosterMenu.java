package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.booster.BoosterCategory;
import com.uxplima.uxmskyblock.core.domain.booster.CategoryBoosterPolicy;
import com.uxplima.uxmskyblock.core.domain.booster.IslandBooster;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Interactive chest GUI presenting active island booster categories, animated progress bars,
 * multipliers, and remaining durations (/is booster).
 */
public final class IslandBoosterMenu {

    private final IslandStoragePort islandStoragePort;
    private final IslandBoosterService boosterService;
    private final BoosterConfiguration configuration;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final @Nullable SchedulerPort schedulerPort;

    public IslandBoosterMenu(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable SchedulerPort schedulerPort) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.boosterService = Objects.requireNonNull(boosterService, "boosterService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = schedulerPort;
    }

    public IslandBoosterMenu(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this(islandStoragePort, boosterService, configuration, sessionCoordinator, null);
    }

    public void open(Player player) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt = sessionCoordinator != null
                ? sessionCoordinator.activeProfile(rawUuid)
                : Optional.empty();

        if (activeOpt.isEmpty()) {
            player.sendMessage(Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        ProfileId profileId = activeOpt.get();
        Runnable asyncTask = () -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                Runnable notify = () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(
                                        "You do not belong to an island! Create one first via /is create.", NamedTextColor.RED)
                                .decoration(TextDecoration.ITALIC, false));
                    }
                };
                if (schedulerPort != null) {
                    schedulerPort.onEntity(playerUuid, notify);
                } else {
                    notify.run();
                }
                return;
            }

            IslandId islandId = optIslandId.get();
            Runnable show = () -> {
                if (player.isOnline()) {
                    SimpleGui gui = buildGui(player, islandId, Instant.now());
                    gui.open(player);
                }
            };
            if (schedulerPort != null) {
                schedulerPort.onEntity(playerUuid, show);
            } else {
                show.run();
            }
        };

        if (schedulerPort != null) {
            schedulerPort.async(asyncTask);
        } else {
            asyncTask.run();
        }
    }

    public SimpleGui buildGui(Player player, IslandId islandId, Instant now) {
        SimpleGui gui = Guis.gui()
                .title(Component.text("Active Island Boosters", NamedTextColor.GOLD))
                .rows(4)
                .build();

        // Border decoration
        ItemStack filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty())
                .build();
        gui.filler().fillBorder(GuiItem.display(filler));

        // Center Overview Header (Slot 4)
        boolean isPaused = boosterService.isIslandPaused(islandId);
        List<IslandBooster> allActive = boosterService.getActiveBoosters(islandId, now);
        ItemStack header = ItemBuilder.of(Material.NETHER_STAR)
                .name(MiniMessage.miniMessage().deserialize("<gold><bold>Island Multiplier Overview</bold></gold>"))
                .lore(
                        List.of(
                                MiniMessage.miniMessage()
                                        .deserialize("<gray>Active Boosters: <white>" + allActive.size()
                                                + "</white></gray>"),
                                MiniMessage.miniMessage()
                                        .deserialize("<gray>Pause-on-Idle: "
                                                + (configuration.pauseWhenEmpty()
                                                        ? (isPaused
                                                                ? "<yellow>PAUSED</yellow>"
                                                                : "<green>ONLINE</green>")
                                                        : "<dark_gray>DISABLED</dark_gray>")
                                                + "</gray>"),
                                MiniMessage.miniMessage()
                                        .deserialize(
                                                "<dark_gray>Booster timers freeze automatically when 0 members are online.</dark_gray>")))
                .build();
        gui.set(4, GuiItem.display(header));

        // Category Cards
        setupCategoryCard(gui, 10, BoosterCategory.SPAWNER_RATE, Material.SPAWNER, islandId, now);
        setupCategoryCard(gui, 12, BoosterCategory.CROP_GROWTH, Material.WHEAT, islandId, now);
        setupCategoryCard(gui, 14, BoosterCategory.ORE_GENERATOR, Material.DIAMOND_ORE, islandId, now);
        setupCategoryCard(gui, 16, BoosterCategory.MOB_EXP, Material.EXPERIENCE_BOTTLE, islandId, now);
        setupCategoryCard(gui, 21, BoosterCategory.ISLAND_WORTH, Material.GOLD_BLOCK, islandId, now);
        setupCategoryCard(gui, 23, BoosterCategory.MISSION_REWARDS, Material.EMERALD, islandId, now);

        // Close button (Slot 31)
        ItemStack closeItem = ItemBuilder.of(Material.BARRIER)
                .name(MiniMessage.miniMessage().deserialize("<red><bold>Close Menu</bold></red>"))
                .build();
        gui.set(31, GuiItem.button(closeItem, event -> player.closeInventory()));

        return gui;
    }

    private void setupCategoryCard(
            SimpleGui gui, int slot, BoosterCategory category, Material icon, IslandId islandId, Instant now) {
        CategoryBoosterPolicy policy = configuration.policy(category);
        List<IslandBooster> active = boosterService.getActiveBoosters(islandId, category, now);
        double effectiveMultiplier = boosterService.getEffectiveMultiplier(islandId, category, now);

        boolean hasActive = !active.isEmpty();
        boolean isPaused = hasActive && active.stream().anyMatch(IslandBooster::isPaused);

        Duration maxDuration = policy.maxDuration();
        long totalRemainingSec = 0;
        for (IslandBooster b : active) {
            totalRemainingSec += b.effectiveRemainingSeconds(now);
        }
        Duration remaining = Duration.ofSeconds(totalRemainingSec);

        double ratio =
                (maxDuration.toSeconds() > 0) ? (double) totalRemainingSec / (double) maxDuration.toSeconds() : 0.0;
        String progressBar = renderProgressBar(ratio, 10);

        String statusStr;
        if (!policy.enabled()) {
            statusStr = "<dark_gray>DISABLED</dark_gray>";
        } else if (isPaused) {
            statusStr = "<yellow>PAUSED (Timer Frozen)</yellow>";
        } else if (hasActive) {
            statusStr = "<green>ACTIVE</green>";
        } else {
            statusStr = "<gray>INACTIVE</gray>";
        }

        ItemStack card = ItemBuilder.of(icon)
                .name(MiniMessage.miniMessage()
                        .deserialize("<gold><bold>" + category.displayName() + " Booster</bold></gold>"))
                .lore(List.of(
                        MiniMessage.miniMessage().deserialize("<gray>Status: " + statusStr),
                        MiniMessage.miniMessage()
                                .deserialize("<gray>Current Multiplier: <yellow><bold>"
                                        + String.format(java.util.Locale.ROOT, "%.2fx", effectiveMultiplier)
                                        + "</bold></yellow></gray>"),
                        MiniMessage.miniMessage()
                                .deserialize("<gray>Remaining Time: <white>" + formatDuration(remaining)
                                        + "</white></gray>"),
                        MiniMessage.miniMessage().deserialize("<gray>Progress: " + progressBar),
                        Component.empty(),
                        MiniMessage.miniMessage()
                                .deserialize("<dark_gray>Stack Mode: "
                                        + policy.stackMode().name() + " | Cap: " + policy.maxMultiplier()
                                        + "x</dark_gray>"),
                        MiniMessage.miniMessage()
                                .deserialize("<dark_gray>Max Duration: " + formatDuration(policy.maxDuration())
                                        + "</dark_gray>")))
                .build();

        gui.set(slot, GuiItem.display(card));
    }

    private String renderProgressBar(double ratio, int totalBars) {
        int filled = (int) Math.round(Math.clamp(ratio, 0.0, 1.0) * totalBars);
        int empty = totalBars - filled;
        return "<green>" + "■".repeat(filled) + "</green><dark_gray>" + "□".repeat(empty) + "</dark_gray>";
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = duration.toSeconds();
        if (totalSeconds <= 0) {
            return "None";
        }
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("d ");
        if (hours > 0) sb.append(hours).append("h ");
        if (minutes > 0) sb.append(minutes).append("m ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append("s");
        return sb.toString().trim();
    }
}
