package com.uxplima.uxmskyblock.bukkit.menu;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
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
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.jspecify.annotations.Nullable;

/**
 * Interactive double-confirmation GUI for island reset and deletion requests.
 */
public final class IslandResetConfirmationMenu {

    private final IslandRecycleService recycleService;
    private final IslandStoragePort islandStoragePort;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;
    private final SchedulerPort schedulerPort;
    private volatile @Nullable BedrockFormService bedrockFormService;
    private final Messages messages;

    public IslandResetConfirmationMenu(
            IslandRecycleService recycleService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            @Nullable BedrockFormService bedrockFormService,
            Messages messages) {
        this.recycleService = Objects.requireNonNull(recycleService, "recycleService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.bedrockFormService = bedrockFormService;
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    public IslandResetConfirmationMenu(
            IslandRecycleService recycleService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            Messages messages) {
        this(recycleService, islandStoragePort, sessionCoordinator, schedulerPort, null, messages);
    }

    public void setBedrockFormService(@Nullable BedrockFormService bedrockFormService) {
        this.bedrockFormService = bedrockFormService;
    }

    /**
     * Opens the confirmation for {@code player}.
     *
     * @param onConfirm what confirming does. It is the command's own confirmation, so a reset from
     *     here is held to the same rules as a typed one: the allowance, the double click guard and
     *     the inventory the operator asked to have emptied.
     */
    public void open(Player player, String verificationCode, Runnable onConfirm) {
        UUID rawUuid = player.getUniqueId();
        PlayerUuid playerUuid = new PlayerUuid(rawUuid);
        Optional<ProfileId> activeOpt =
                sessionCoordinator != null ? sessionCoordinator.activeProfile(rawUuid) : Optional.empty();

        if (activeOpt.isEmpty()) {
            messages.send(player, "error.session_not_active");
            return;
        }

        ProfileId profileId = activeOpt.get();
        schedulerPort.async(() -> {
            Optional<IslandId> optIslandId = islandStoragePort.findIslandIdByProfileId(profileId);
            if (optIslandId.isEmpty()) {
                schedulerPort.onEntity(playerUuid, () -> {
                    if (player.isOnline()) {
                        messages.send(player, "error.no_island");
                    }
                });
                return;
            }

            IslandId islandId = optIslandId.get();
            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (bedrockFormService != null && bedrockFormService.isBedrock(player)) {
                    bedrockFormService.openConfirmationModal(
                            player,
                            legacy(messages.renderPlain(player, "menu.reset.title")),
                            legacy(messages.renderPlain(
                                    player, "menu.reset.form_body", Placeholder.unparsed("code", verificationCode))),
                            legacy(messages.renderPlain(player, "menu.reset.confirm_name")),
                            legacy(messages.renderPlain(player, "menu.reset.cancel_name")),
                            onConfirm,
                            () -> {
                                recycleService.cancelResetChallenge(profileId);
                                messages.send(player, "menu.reset.cancelled");
                            });
                    return;
                }
                SimpleGui gui = buildGui(player, profileId, islandId, verificationCode, onConfirm);
                gui.open(player);
            });
        });
    }

    /** A Floodgate form takes a legacy string, so a component has to be flattened for it. */
    private static String legacy(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection()
                .serialize(component);
    }

    public SimpleGui buildGui(
            Player player, ProfileId profileId, IslandId islandId, String verificationCode, Runnable onConfirm) {
        SimpleGui gui = Guis.gui()
                .title(messages.renderPlain(player, "menu.reset.title"))
                .rows(3)
                .build();

        // Filler
        ItemStack filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.space())
                .build();
        gui.filler().fill(GuiItem.display(filler));

        // Info icon in center
        ItemStack infoItem = ItemBuilder.of(Material.BARRIER)
                .name(messages.renderPlain(player, "menu.reset.info_name"))
                .lore(messages.renderAll(
                        player, "menu.reset.info_lore", Placeholder.unparsed("code", verificationCode)))
                .build();
        gui.set(13, GuiItem.display(infoItem));

        // Confirm button
        ItemStack confirmItem = ItemBuilder.of(Material.RED_CONCRETE)
                .name(messages.renderPlain(player, "menu.reset.confirm_name"))
                .lore(messages.renderAll(player, "menu.reset.confirm_lore"))
                .build();

        gui.set(11, GuiItem.button(confirmItem, event -> {
            player.closeInventory();
            onConfirm.run();
        }));

        // Cancel button
        ItemStack cancelItem = ItemBuilder.of(Material.GREEN_CONCRETE)
                .name(messages.renderPlain(player, "menu.reset.cancel_name"))
                .lore(messages.renderAll(player, "menu.reset.cancel_lore"))
                .build();

        gui.set(15, GuiItem.button(cancelItem, event -> {
            player.closeInventory();
            recycleService.cancelResetChallenge(profileId);
            messages.send(player, "menu.reset.cancelled");
        }));

        return gui;
    }
}
