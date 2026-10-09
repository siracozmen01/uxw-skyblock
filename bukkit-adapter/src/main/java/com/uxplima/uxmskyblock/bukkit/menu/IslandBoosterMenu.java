package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.gui.style.MenuTitles;
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.config.BoosterConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.DurationText;
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

    private volatile @Nullable Consumer<Player> wayBack;

    private volatile @Nullable SkyblockMenuEngine menuEngine;

    /** The menu file that draws the boosters, and the list it draws a card from for each kind. */
    static final String FILE = "island-booster-cards";

    static final String CARDS = "skyblock:booster-cards";

    /** The kinds of booster in the order the cards are drawn, and the icon each one wears. */
    private static final Map<BoosterCategory, Material> ICONS = new java.util.LinkedHashMap<>();

    static {
        ICONS.put(BoosterCategory.SPAWNER_RATE, Material.SPAWNER);
        ICONS.put(BoosterCategory.CROP_GROWTH, Material.WHEAT);
        ICONS.put(BoosterCategory.ORE_GENERATOR, Material.DIAMOND_ORE);
        ICONS.put(BoosterCategory.MOB_EXP, Material.EXPERIENCE_BOTTLE);
        ICONS.put(BoosterCategory.ISLAND_WORTH, Material.GOLD_BLOCK);
        ICONS.put(BoosterCategory.MISSION_REWARDS, Material.EMERALD);
    }

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
                SkyblockMenuEngine engine = this.menuEngine;
                if (engine != null
                        && engine.open(
                                player, FILE, overviewValues(overview), Map.of(CARDS, rows(player, overview, now)))) {
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
            String label = com.uxplima.uxmskyblock.bukkit.bedrock.FormText.of(messages.renderPlain(
                    player,
                    "menu.booster.form_button",
                    Placeholder.unparsed(
                            "category",
                            messages.named(player, "booster.categories", category.name(), category.displayName())),
                    Placeholder.component("status", messages.renderPlain(player, statusKey)),
                    Placeholder.unparsed(
                            "multiplier",
                            String.format(java.util.Locale.ROOT, "%.2f", overview.multiplierOf(category))),
                    Placeholder.unparsed(
                            "remaining", DurationText.of(messages, player, Duration.ofSeconds(remainingSeconds)))));
            choices.add(new BedrockFormService.Choice(label, () -> {}));
        }
        forms.openChoiceForm(player, "menu.booster.title", "menu.booster.form_body", choices);
    }

    /**
     * Hands this window the engine that reads {@code menus/island-booster-cards.conf}. The window built
     * here stays as the answer to a file that is missing or will not parse.
     */
    public void useMenuEngine(@Nullable SkyblockMenuEngine engine) {
        this.menuEngine = engine;
        if (engine != null) {
            engine.handedList(CARDS);
        }
    }

    /** The overview the file draws over the cards: how many run, and what an empty island does to them. */
    Map<String, String> overviewValues(IslandBoosterService.BoosterOverview overview) {
        return Map.of("count", Integer.toString(overview.active().size()), "state", "<key:" + idleKey(overview) + ">");
    }

    private String idleKey(IslandBoosterService.BoosterOverview overview) {
        return configuration.pauseWhenEmpty()
                ? (overview.paused() ? "menu.booster.idle_paused" : "menu.booster.idle_online")
                : "menu.booster.idle_disabled";
    }

    /** One card per kind of booster: its state, its multiplier, how long it has left and how it stacks. */
    List<MenuRow> rows(Player player, IslandBoosterService.BoosterOverview overview, Instant now) {
        List<MenuRow> rows = new ArrayList<>();
        for (Map.Entry<BoosterCategory, Material> kind : ICONS.entrySet()) {
            Card card = card(kind.getKey(), overview, now);
            CategoryBoosterPolicy policy = card.policy();
            Map<String, String> words = new java.util.HashMap<>();
            words.put("material", kind.getValue().name());
            words.put(
                    "category",
                    messages.named(
                            player,
                            "booster.categories",
                            kind.getKey().name(),
                            kind.getKey().displayName()));
            words.put("status", "<key:" + card.statusKey() + ">");
            words.put("multiplier", String.format(java.util.Locale.ROOT, "%.2f", overview.multiplierOf(kind.getKey())));
            words.put("remaining", DurationText.of(messages, player, card.remaining()));
            words.put("bar", bar(card.ratio(), 10));
            words.put(
                    "mode",
                    messages.named(
                            player,
                            "booster.stack_modes",
                            policy.stackMode().name(),
                            policy.stackMode().name()));
            words.put("cap", String.format(java.util.Locale.ROOT, "%.2f", policy.maxMultiplier()));
            words.put("duration", DurationText.of(messages, player, policy.maxDuration()));
            rows.add(new MenuRow(words, kind.getKey()));
        }
        return List.copyOf(rows);
    }

    /** Where one kind of booster stands now. */
    private record Card(CategoryBoosterPolicy policy, String statusKey, Duration remaining, double ratio) {}

    private Card card(BoosterCategory category, IslandBoosterService.BoosterOverview overview, Instant now) {
        CategoryBoosterPolicy policy = configuration.policy(category);
        List<IslandBooster> active = overview.activeIn(category);
        boolean hasActive = !active.isEmpty();
        boolean isPaused = hasActive && active.stream().anyMatch(IslandBooster::isPaused);
        long totalRemainingSec = 0;
        for (IslandBooster b : active) {
            totalRemainingSec += b.effectiveRemainingSeconds(now);
        }
        Duration maxDuration = policy.maxDuration();
        double ratio =
                (maxDuration.toSeconds() > 0) ? (double) totalRemainingSec / (double) maxDuration.toSeconds() : 0.0;
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
        return new Card(policy, statusKey, Duration.ofSeconds(totalRemainingSec), ratio);
    }

    /** The progress bar as catalogue pieces, a filled one per step reached and an empty one for the rest. */
    private static String bar(double ratio, int totalBars) {
        int filled = (int) Math.round(Math.clamp(ratio, 0.0, 1.0) * totalBars);
        return "<key:menu.booster.bar_filled>".repeat(filled)
                + "<key:menu.booster.bar_empty>".repeat(totalBars - filled);
    }

    /**
     * Hands this window the way back to the menu that opened it, so the bottom row reads "Back" rather
     * than leaving Escape as the only way out. Without one the window has no back button at all.
     */
    public void useWayBack(@Nullable Consumer<Player> wayBack) {
        this.wayBack = wayBack;
    }

    public SimpleGui buildGui(Player player, IslandBoosterService.BoosterOverview overview, Instant now) {
        SimpleGui gui = Guis.gui()
                .title(MenuTitles.centre(messages.renderPlain(player, "menu.booster.title")))
                .rows(4)
                .build();
        gui.filler().fill(GuiItem.display(SkyblockTiles.filler()));

        SkyblockTiles tiles = new SkyblockTiles(messages);
        String idleKey = configuration.pauseWhenEmpty()
                ? (overview.paused() ? "menu.booster.idle_paused" : "menu.booster.idle_online")
                : "menu.booster.idle_disabled";
        gui.set(
                4,
                GuiItem.display(tiles.item(
                        Material.NETHER_STAR,
                        player,
                        "tile:event @menu.booster.overview active idle",
                        Placeholder.unparsed(
                                "argument_count",
                                Integer.toString(overview.active().size())),
                        Placeholder.component("argument_state", messages.renderPlain(player, idleKey)))));

        setupCategoryCard(tiles, player, gui, 10, BoosterCategory.SPAWNER_RATE, Material.SPAWNER, overview, now);
        setupCategoryCard(tiles, player, gui, 12, BoosterCategory.CROP_GROWTH, Material.WHEAT, overview, now);
        setupCategoryCard(tiles, player, gui, 14, BoosterCategory.ORE_GENERATOR, Material.DIAMOND_ORE, overview, now);
        setupCategoryCard(tiles, player, gui, 16, BoosterCategory.MOB_EXP, Material.EXPERIENCE_BOTTLE, overview, now);
        setupCategoryCard(tiles, player, gui, 21, BoosterCategory.ISLAND_WORTH, Material.GOLD_BLOCK, overview, now);
        setupCategoryCard(tiles, player, gui, 23, BoosterCategory.MISSION_REWARDS, Material.EMERALD, overview, now);

        Consumer<Player> back = this.wayBack;
        if (back != null) {
            gui.set(31, GuiItem.button(tiles.button(Material.FEATHER, player, "menu.button.back"), event -> {
                event.setCancelled(true);
                back.accept(player);
            }));
        }
        return gui;
    }

    private void setupCategoryCard(
            SkyblockTiles tiles,
            Player player,
            SimpleGui gui,
            int slot,
            BoosterCategory category,
            Material icon,
            IslandBoosterService.BoosterOverview overview,
            Instant now) {
        CategoryBoosterPolicy policy = configuration.policy(category);
        List<IslandBooster> active = overview.activeIn(category);
        boolean hasActive = !active.isEmpty();
        boolean isPaused = hasActive && active.stream().anyMatch(IslandBooster::isPaused);

        long totalRemainingSec = 0;
        for (IslandBooster b : active) {
            totalRemainingSec += b.effectiveRemainingSeconds(now);
        }
        Duration maxDuration = policy.maxDuration();
        double ratio =
                (maxDuration.toSeconds() > 0) ? (double) totalRemainingSec / (double) maxDuration.toSeconds() : 0.0;

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

        ItemStack card = tiles.item(
                icon,
                player,
                "tile:event @menu.booster.card status multiplier remaining progress stacking longest",
                Placeholder.unparsed(
                        "entry_category",
                        messages.named(player, "booster.categories", category.name(), category.displayName())),
                Placeholder.component("entry_status", messages.renderPlain(player, statusKey)),
                Placeholder.unparsed(
                        "entry_multiplier",
                        String.format(java.util.Locale.ROOT, "%.2f", overview.multiplierOf(category))),
                Placeholder.unparsed(
                        "entry_remaining", DurationText.of(messages, player, Duration.ofSeconds(totalRemainingSec))),
                Placeholder.component("entry_bar", renderProgressBar(player, ratio, 10)),
                Placeholder.unparsed(
                        "entry_mode",
                        messages.named(
                                player,
                                "booster.stack_modes",
                                policy.stackMode().name(),
                                policy.stackMode().name())),
                Placeholder.unparsed("entry_cap", String.format(java.util.Locale.ROOT, "%.2f", policy.maxMultiplier())),
                Placeholder.unparsed("entry_duration", DurationText.of(messages, player, policy.maxDuration())));
        gui.set(slot, GuiItem.display(card));
    }

    /**
     * The bar, one catalogue piece per step. It was a string of colour tags put into the line as text,
     * so the player read the tags; the pieces are now components, and an operator draws them.
     */
    private Component renderProgressBar(Player player, double ratio, int totalBars) {
        int filled = (int) Math.round(Math.clamp(ratio, 0.0, 1.0) * totalBars);
        Component full = messages.renderPlain(player, "menu.booster.bar_filled");
        Component blank = messages.renderPlain(player, "menu.booster.bar_empty");
        net.kyori.adventure.text.TextComponent.Builder bar = Component.text();
        for (int step = 0; step < totalBars; step++) {
            bar.append(step < filled ? full : blank);
        }
        return bar.build();
    }
}
