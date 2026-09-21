package com.uxplima.uxmskyblock.bukkit.menu;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmlib.gui.Guis;
import com.uxplima.uxmlib.gui.SimpleGui;
import com.uxplima.uxmlib.gui.item.GuiItem;
import com.uxplima.uxmlib.item.ItemBuilder;
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

    private final IslandMissionService missionService;
    private final IslandStoragePort islandStoragePort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private final Messages messages;

    public IslandMissionsMenu(
            IslandMissionService missionService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages) {
        this.missionService = Objects.requireNonNull(missionService, "missionService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public void open(Player player) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt =
                sessionCoordinator != null ? sessionCoordinator.activeProfile(rawUuid) : Optional.empty();

        if (activeOpt.isEmpty()) {
            player.sendMessage(Component.text(
                            "Your profile session is not active or still loading. Please wait.", NamedTextColor.RED)
                    .decoration(TextDecoration.ITALIC, false));
            return;
        }

        ProfileId profileId = activeOpt.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        player.sendMessage(Component.text(
                                        "You do not belong to an island! Create one first via /is create.",
                                        NamedTextColor.RED)
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
                SimpleGui gui = buildGui(player, islandId, profileId, progressMap, all);
                gui.open(player);
            });
        });
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
                    handleManualItemSubmission(player, islandId, profileId, def);
                }
            });

            gui.set(slot++, guiItem);
        }

        return gui;
    }

    private void handleManualItemSubmission(
            Player player, IslandId islandId, ProfileId profileId, MissionDefinition def) {
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

        // Consume up to required amount
        Map<com.uxplima.uxmskyblock.core.domain.mission.MissionId, MissionProgress> progressMap =
                missionService.findAllProgress(islandId, profileId);
        MissionProgress current = progressMap.get(def.id());
        long currentCount = current != null ? current.progressCount() : 0L;
        long needed = def.requiredAmount() - currentCount;
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
            missionService.submitManualItem(islandId, profileId, def.id(), taken, Instant.now());
            schedulerPort.onEntity(new PlayerUuid(player.getUniqueId()), () -> {
                messages.send(
                        player,
                        "menu.missions.submitted",
                        Placeholder.unparsed("amount", Integer.toString(taken)),
                        Placeholder.unparsed("item", requiredMat.name()));
                open(player);
            });
        });
    }
}
