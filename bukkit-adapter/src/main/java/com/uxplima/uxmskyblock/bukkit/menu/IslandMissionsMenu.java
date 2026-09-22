package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.mission.IslandMissionService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.mission.MissionDefinition;
import com.uxplima.uxmskyblock.core.domain.mission.MissionProgress;
import com.uxplima.uxmskyblock.core.domain.mission.MissionTriggerType;
import org.jspecify.annotations.Nullable;

/**
 * Interactive chest GUI displaying available mission branches, quest tracking,
 * and physical item submissions.
 */
public final class IslandMissionsMenu {

    private static final Logger LOGGER = Logger.getLogger(IslandMissionsMenu.class.getName());

    private final IslandMissionService missionService;
    private final IslandStoragePort islandStoragePort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private @Nullable BedrockFormService bedrockFormService;

    public IslandMissionsMenu(
            IslandMissionService missionService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable BedrockFormService bedrockFormService) {
        this.missionService = Objects.requireNonNull(missionService, "missionService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.bedrockFormService = bedrockFormService;
    }

    /**
     * The Bedrock screen is built after this window is, so it arrives here rather than in the
     * constructor. Until it does, a Bedrock player gets the chest window.
     */
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
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(messages.render(player, "menu.control.no_island")
                                .decoration(TextDecoration.ITALIC, false));
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap =
                    missionService.findAllProgress(islandId, profileId);
            List<MissionDefinition> all = missionService.allMissions();

            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                BedrockFormService forms = this.bedrockFormService;
                if (forms != null && forms.isBedrock(player)) {
                    openForm(forms, player, islandId, profileId, progressMap, all);
                    return;
                }
                SimpleGui gui = buildGui(player, islandId, profileId, progressMap, all);
                gui.open(player);
            });
        });
    }

    /**
     * The same window for a Bedrock player, as a native form rather than a chest they cannot use
     * properly. The buttons are the same missions in the same order, and a mission that takes items
     * submits them from here too.
     */
    private void openForm(
            BedrockFormService forms,
            Player player,
            IslandId islandId,
            ProfileId profileId,
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap,
            List<MissionDefinition> all) {
        List<BedrockFormService.Choice> choices = new ArrayList<>();
        for (MissionDefinition def : all) {
            MissionProgress progress = progressMap.get(def.id());
            long count = progress != null ? progress.progressCount() : 0L;
            boolean completed = progress != null && progress.completed();
            Component status = completed
                    ? messages.renderPlain(player, "menu.missions.status_completed")
                    : messages.renderPlain(
                            player,
                            "menu.missions.status_open",
                            Placeholder.unparsed("count", Long.toString(count)),
                            Placeholder.unparsed("required", Long.toString(def.requiredAmount())));
            String label = LegacyComponentSerializer.legacySection()
                    .serialize(messages.renderPlain(
                            player,
                            "menu.missions.form_button",
                            Placeholder.unparsed("mission", def.displayName()),
                            Placeholder.component("status", status)));
            boolean submittable = !completed && def.triggerType() == MissionTriggerType.ITEM_SUBMIT;
            long drawnWith = count;
            choices.add(new BedrockFormService.Choice(label, () -> {
                if (submittable) {
                    handleManualItemSubmission(player, islandId, profileId, def, drawnWith);
                }
            }));
        }
        forms.openChoiceForm(player, "menu.missions.title", "menu.missions.form_body", choices);
    }

    public SimpleGui buildGui(
            Player player,
            IslandId islandId,
            ProfileId profileId,
            Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap,
            List<MissionDefinition> all) {
        SimpleGui gui = Guis.gui()
                .title(messages.renderPlain(player, "menu.missions.title"))
                .rows(6)
                .build();

        // Border decoration
        ItemStack filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.empty())
                .build();
        gui.filler().fillBorder(GuiItem.display(filler));

        int slot = 10;
        for (MissionDefinition def : all) {
            if (slot > 43) break;
            if (slot % 9 == 8) slot += 2; // skip border columns

            MissionProgress progress = progressMap.get(def.id());
            long count = progress != null ? progress.progressCount() : 0L;
            boolean completed = progress != null && progress.completed();

            Material icon = completed
                    ? Material.ENCHANTED_BOOK
                    : (def.triggerType() == MissionTriggerType.ITEM_SUBMIT ? Material.CHEST : Material.BOOK);

            Component status = completed
                    ? messages.renderPlain(player, "menu.missions.status_completed")
                    : messages.renderPlain(
                            player,
                            "menu.missions.status_open",
                            Placeholder.unparsed("count", Long.toString(count)),
                            Placeholder.unparsed("required", Long.toString(def.requiredAmount())));

            List<Component> lore = new ArrayList<>();
            lore.add(messages.renderPlain(
                    player, "menu.missions.tile_description", Placeholder.unparsed("description", def.description())));
            lore.add(Component.empty());
            lore.add(messages.renderPlain(
                    player,
                    "menu.missions.tile_branch",
                    Placeholder.unparsed("branch", def.branch().name())));
            lore.add(messages.renderPlain(
                    player, "menu.missions.tile_progress", Placeholder.component("status", status)));
            lore.add(Component.empty());
            lore.add(messages.renderPlain(player, "menu.missions.tile_rewards"));
            if (def.reward().crystals() > 0) {
                lore.add(messages.renderPlain(
                        player,
                        "menu.missions.reward_crystals",
                        Placeholder.unparsed(
                                "amount", Long.toString(def.reward().crystals()))));
            }
            if (def.reward().currencyMinorUnits() > 0) {
                lore.add(messages.renderPlain(
                        player,
                        "menu.missions.reward_currency",
                        Placeholder.unparsed(
                                "amount",
                                String.format(Locale.US, "%.2f", def.reward().currencyMinorUnits() / 100.0))));
            }
            if (def.reward().islandExp() > 0) {
                lore.add(messages.renderPlain(
                        player,
                        "menu.missions.reward_island_exp",
                        Placeholder.unparsed(
                                "amount", Long.toString(def.reward().islandExp()))));
            }

            if (!completed && def.triggerType() == MissionTriggerType.ITEM_SUBMIT) {
                lore.add(Component.empty());
                lore.add(messages.renderPlain(player, "menu.missions.tile_submit_hint"));
            }

            ItemStack item = ItemBuilder.of(icon)
                    .name(messages.renderPlain(
                                    player,
                                    completed ? "menu.missions.tile_name_done" : "menu.missions.tile_name_open",
                                    Placeholder.unparsed("mission", def.displayName()))
                            .decoration(TextDecoration.ITALIC, false))
                    .lore(lore)
                    .build();

            GuiItem guiItem = GuiItem.button(item, event -> {
                event.setCancelled(true);
                if (!completed && def.triggerType() == MissionTriggerType.ITEM_SUBMIT) {
                    handleManualItemSubmission(player, islandId, profileId, def, count);
                }
            });

            gui.set(slot++, guiItem);
        }

        return gui;
    }

    /** Package private so the guard against losing a player's items can drive it directly. */
    void handleManualItemSubmission(
            Player player, IslandId islandId, ProfileId profileId, MissionDefinition def, long alreadyCounted) {
        String filter = def.targetFilter();
        Material requiredMat = Material.matchMaterial(filter);
        if (requiredMat == null) {
            messages.send(player, "menu.missions.invalid_requirement", Placeholder.unparsed("filter", filter));
            return;
        }

        int count = 0;
        for (ItemStack is : player.getInventory().getContents()) {
            if (is != null && is.getType() == requiredMat) {
                count += is.getAmount();
            }
        }

        if (count <= 0) {
            messages.send(player, "menu.missions.nothing_to_submit", Placeholder.unparsed("item", requiredMat.name()));
            return;
        }

        // How many the mission still wanted when this window was drawn. Reading it again here would
        // be a query on the thread that owns the player, and the answer is already on the tile the
        // player just clicked. What the service actually credits is what it reports back, and
        // anything it did not take is handed straight back below.
        long needed = def.requiredAmount() - alreadyCounted;
        int toTake = (int) Math.min(count, needed);

        if (toTake <= 0) {
            return;
        }

        int remainingToTake = toTake;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack is = player.getInventory().getItem(i);
            if (is != null && is.getType() == requiredMat) {
                int amt = is.getAmount();
                if (amt <= remainingToTake) {
                    player.getInventory().setItem(i, null);
                    remainingToTake -= amt;
                } else {
                    is.setAmount(amt - remainingToTake);
                    remainingToTake = 0;
                }
                if (remainingToTake <= 0) break;
            }
        }

        final int taken = toTake;
        schedulerPort.async(() -> {
            long credited;
            try {
                credited = missionService
                        .submitManualItems(islandId, profileId, def.id(), taken, Instant.now())
                        .map(IslandMissionService.MissionSubmission::credited)
                        .orElse(0L);
            } catch (RuntimeException e) {
                LOGGER.log(
                        Level.SEVERE,
                        "Crediting a manual mission submission failed, returning the items to the player",
                        e);
                credited = 0L;
            }

            long creditedFinal = credited;
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                // Whatever the mission did not take goes back. A window drawn a moment ago can say
                // the mission wants ten when another window has already handed in eight, and a
                // player who loses the other two to a counter that stopped rising has been robbed
                // by their own second window.
                int surplus = (int) (taken - creditedFinal);
                if (surplus > 0) {
                    returnItems(player, requiredMat, surplus);
                }
                if (creditedFinal <= 0) {
                    messages.send(
                            player, "menu.missions.submit_failed", Placeholder.unparsed("item", requiredMat.name()));
                    return;
                }
                messages.send(
                        player,
                        "menu.missions.submitted",
                        Placeholder.unparsed("amount", Long.toString(creditedFinal)),
                        Placeholder.unparsed("item", requiredMat.name()));
                open(player);
            });
        });
    }

    /**
     * Gives back what was taken when the submission was not credited.
     *
     * <p>The items leave the inventory before the progress is written, because the write is off the
     * entity thread and the inventory may not be touched from there. If the write then does nothing,
     * either because it failed or because the mission was already finished by somebody else, the
     * player has paid for nothing. What does not fit goes on the ground at their feet rather than
     * being dropped silently.
     */
    private void returnItems(Player player, Material material, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack stack = new ItemStack(material, amount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(stack);
        for (ItemStack overflow : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), overflow);
        }
    }
}
