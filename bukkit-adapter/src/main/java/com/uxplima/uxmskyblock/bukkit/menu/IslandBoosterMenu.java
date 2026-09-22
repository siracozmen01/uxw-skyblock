package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
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
    private @Nullable BedrockFormService bedrockFormService;

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

    /** The Bedrock screen is built after this window is, so it arrives here rather than in the constructor. */
    public void setBedrockFormService(@Nullable BedrockFormService bedrockFormService) {
        this.bedrockFormService = bedrockFormService;
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
            // Every number the window shows comes out of one read, taken here, off the thread that
            // owns the player. It used to ask fourteen separate questions while that thread waited:
            // the paused flag, every active booster, then the active boosters and the effective
            // multiplier of each of six categories.
            Instant now = Instant.now();
            IslandBoosterService.BoosterOverview overview = boosterService.overview(islandId, now);
            Runnable show = () -> {
                if (!player.isOnline()) {
                    return;
                }
                BedrockFormService forms = this.bedrockFormService;
                if (forms != null && forms.isBedrock(player)) {
                    openForm(forms, player, overview, now);
                    return;
                }
                SimpleGui gui = buildGui(player, overview, now);
                gui.open(player);
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

    /**
     * The same overview for a Bedrock player, as a native form. The window is read only on both
     * sides, so every button closes it: what matters is that the numbers are readable at all, which
     * they are not in a chest a Bedrock client renders as a list of icons.
     */
    private void openForm(
            BedrockFormService forms, Player player, IslandBoosterService.BoosterOverview overview, Instant now) {
        List<BedrockFormService.Choice> choices = new ArrayList<>();
        for (BoosterCategory category : BoosterCategory.values()) {
            CategoryBoosterPolicy policy = configuration.policy(category);
            List<IslandBooster> active = overview.activeIn(category);
            boolean hasActive = !active.isEmpty();
            boolean paused = hasActive && active.stream().anyMatch(IslandBooster::isPaused);
            String statusKey;
            if (!policy.enabled()) {
                statusKey = "menu.booster.status_disabled";
            } else if (paused) {
                statusKey = "menu.booster.status_paused";
            } else if (hasActive) {
                statusKey = "menu.booster.status_active";
            } else {
                statusKey = "menu.booster.status_inactive";
            }
            long remainingSeconds = 0;
            for (IslandBooster booster : active) {
                remainingSeconds += booster.effectiveRemainingSeconds(now);
            }
            String label = LegacyComponentSerializer.legacySection()
                    .serialize(messages.renderPlain(
                            player,
                            "menu.booster.form_button",
                            Placeholder.unparsed("category", category.displayName()),
                            Placeholder.component("status", messages.renderPlain(player, statusKey)),
                            Placeholder.unparsed(
                                    "multiplier",
                                    String.format(java.util.Locale.ROOT, "%.2f", overview.multiplierOf(category))),
                            Placeholder.unparsed("remaining", formatDuration(Duration.ofSeconds(remainingSeconds)))));
            choices.add(new BedrockFormService.Choice(label, () -> {}));
        }
        forms.openChoiceForm(player, "menu.booster.title", "menu.booster.form_body", choices);
    }

    public SimpleGui buildGui(Player player, IslandBoosterService.BoosterOverview overview, Instant now) {
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
        boolean isPaused = overview.paused();
        List<IslandBooster> allActive = overview.active();
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
        setupCategoryCard(player, gui, 10, BoosterCategory.SPAWNER_RATE, Material.SPAWNER, overview, now);
        setupCategoryCard(player, gui, 12, BoosterCategory.CROP_GROWTH, Material.WHEAT, overview, now);
        setupCategoryCard(player, gui, 14, BoosterCategory.ORE_GENERATOR, Material.DIAMOND_ORE, overview, now);
        setupCategoryCard(player, gui, 16, BoosterCategory.MOB_EXP, Material.EXPERIENCE_BOTTLE, overview, now);
        setupCategoryCard(player, gui, 21, BoosterCategory.ISLAND_WORTH, Material.GOLD_BLOCK, overview, now);
        setupCategoryCard(player, gui, 23, BoosterCategory.MISSION_REWARDS, Material.EMERALD, overview, now);

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
            IslandBoosterService.BoosterOverview overview,
            Instant now) {
        CategoryBoosterPolicy policy = configuration.policy(category);
        List<IslandBooster> active = overview.activeIn(category);
        double effectiveMultiplier = overview.multiplierOf(category);

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
