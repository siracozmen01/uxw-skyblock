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
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
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
    private final Messages messages;

    public IslandBoosterMenu(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            @Nullable SchedulerPort schedulerPort,
            Messages messages) {
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.boosterService = Objects.requireNonNull(boosterService, "boosterService must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.schedulerPort = schedulerPort;
    }

    public IslandBoosterMenu(
            IslandStoragePort islandStoragePort,
            IslandBoosterService boosterService,
            BoosterConfiguration configuration,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            Messages messages) {
        this(islandStoragePort, boosterService, configuration, sessionCoordinator, null, messages);
    }

    public void open(Player player) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt =
                sessionCoordinator != null ? sessionCoordinator.activeProfile(rawUuid) : Optional.empty();

        if (activeOpt.isEmpty()) {
            player.sendMessage(
                    messages.render(player, "error.session_not_active").decoration(TextDecoration.ITALIC, false));
            return;
        }

        ProfileId profileId = activeOpt.get();
        Runnable asyncTask = () -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                Runnable notify = () -> {
                    if (player.isOnline()) {
                        player.sendMessage(messages.render(player, "menu.control.no_island")
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
                .title(messages.renderPlain(player, "menu.booster.title"))
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
        String idleKey = configuration.pauseWhenEmpty()
                ? (isPaused ? "menu.booster.idle_paused" : "menu.booster.idle_online")
                : "menu.booster.idle_disabled";
        ItemStack header = ItemBuilder.of(Material.NETHER_STAR)
                .name(messages.renderPlain(player, "menu.booster.overview_name"))
                .lore(List.of(
                        messages.renderPlain(
                                player,
                                "menu.booster.overview_active",
                                Placeholder.unparsed("count", Integer.toString(allActive.size()))),
                        messages.renderPlain(
                                player,
                                "menu.booster.overview_idle",
                                Placeholder.component("state", messages.renderPlain(player, idleKey))),
                        messages.renderPlain(player, "menu.booster.overview_note")))
                .build();
        gui.set(4, GuiItem.display(header));

        // Category Cards
        setupCategoryCard(player, gui, 10, BoosterCategory.SPAWNER_RATE, Material.SPAWNER, islandId, now);
        setupCategoryCard(player, gui, 12, BoosterCategory.CROP_GROWTH, Material.WHEAT, islandId, now);
        setupCategoryCard(player, gui, 14, BoosterCategory.ORE_GENERATOR, Material.DIAMOND_ORE, islandId, now);
        setupCategoryCard(player, gui, 16, BoosterCategory.MOB_EXP, Material.EXPERIENCE_BOTTLE, islandId, now);
        setupCategoryCard(player, gui, 21, BoosterCategory.ISLAND_WORTH, Material.GOLD_BLOCK, islandId, now);
        setupCategoryCard(player, gui, 23, BoosterCategory.MISSION_REWARDS, Material.EMERALD, islandId, now);

        // Close button (Slot 31)
        ItemStack closeItem = ItemBuilder.of(Material.BARRIER)
                .name(messages.renderPlain(player, "menu.booster.close"))
                .build();
        gui.set(31, GuiItem.button(closeItem, event -> player.closeInventory()));

        return gui;
    }

    private void setupCategoryCard(
            Player player,
            SimpleGui gui,
            int slot,
            BoosterCategory category,
            Material icon,
            IslandId islandId,
            Instant now) {
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

        String statusKey;
        if (!policy.enabled()) {
            statusKey = "menu.booster.status_disabled";
        } else if (isPaused) {
            statusKey = "menu.booster.status_paused";
        } else if (hasActive) {
            statusKey = "menu.booster.status_active";
        } else {
            statusKey = "menu.booster.status_inactive";
        }

        ItemStack card = ItemBuilder.of(icon)
                .name(messages.renderPlain(
                        player, "menu.booster.card_name", Placeholder.unparsed("category", category.displayName())))
                .lore(List.of(
                        messages.renderPlain(
                                player,
                                "menu.booster.card_status",
                                Placeholder.component("status", messages.renderPlain(player, statusKey))),
                        messages.renderPlain(
                                player,
                                "menu.booster.card_multiplier",
                                Placeholder.unparsed(
                                        "multiplier",
                                        String.format(java.util.Locale.ROOT, "%.2f", effectiveMultiplier))),
                        messages.renderPlain(
                                player,
                                "menu.booster.card_remaining",
                                Placeholder.unparsed("remaining", formatDuration(remaining))),
                        messages.renderPlain(
                                player, "menu.booster.card_progress", Placeholder.unparsed("bar", progressBar)),
                        Component.empty(),
                        messages.renderPlain(
                                player,
                                "menu.booster.card_stacking",
                                Placeholder.unparsed("mode", policy.stackMode().name()),
                                Placeholder.unparsed(
                                        "cap", String.format(java.util.Locale.ROOT, "%.2f", policy.maxMultiplier()))),
                        messages.renderPlain(
                                player,
                                "menu.booster.card_max_duration",
                                Placeholder.unparsed("duration", formatDuration(policy.maxDuration())))))
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
