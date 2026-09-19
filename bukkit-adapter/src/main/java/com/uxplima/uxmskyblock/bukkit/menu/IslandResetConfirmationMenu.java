package com.uxplima.uxmskyblock.bukkit.menu;

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
import com.uxplima.uxmskyblock.bukkit.bedrock.BedrockFormService;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;
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
    private final @Nullable BedrockFormService bedrockFormService;

    public IslandResetConfirmationMenu(
            IslandRecycleService recycleService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort,
            @Nullable BedrockFormService bedrockFormService) {
        this.recycleService = Objects.requireNonNull(recycleService, "recycleService must not be null");
        this.islandStoragePort = Objects.requireNonNull(islandStoragePort, "islandStoragePort must not be null");
        this.sessionCoordinator = sessionCoordinator;
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.bedrockFormService = bedrockFormService;
    }

    public IslandResetConfirmationMenu(
            IslandRecycleService recycleService,
            IslandStoragePort islandStoragePort,
            @Nullable PlayerSessionCoordinator sessionCoordinator,
            SchedulerPort schedulerPort) {
        this(recycleService, islandStoragePort, sessionCoordinator, schedulerPort, null);
    }

    public void open(Player player, String verificationCode) {
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
                        player.sendMessage(MiniMessage.miniMessage()
                                .deserialize("<red>You do not have an active island to reset.</red>"));
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
                            "Confirm Island Reset",
                            "WARNING: This action CANNOT BE UNDONE!\nConfirmation Code: " + verificationCode
                                    + "\nAll island blocks, items, and bank funds will be wiped.",
                            "§cCONFIRM RESET",
                            "§aCANCEL",
                            () -> schedulerPort.onEntity(playerUuid, () -> {
                                RecycleResult result =
                                        recycleService.executeReset(profileId, islandId, verificationCode, false);
                                switch (result) {
                                    case RecycleResult.Success s -> {
                                        player.sendMessage(
                                                MiniMessage.miniMessage()
                                                        .deserialize(
                                                                "<green><bold>Your island has been reset and recycled successfully!</bold></green>"));
                                        player.sendMessage(
                                                MiniMessage.miniMessage()
                                                        .deserialize(
                                                                "<gray>Create a new island with <yellow>/is create</yellow>.</gray>"));
                                    }
                                    case RecycleResult.NotOwner no ->
                                        player.sendMessage(MiniMessage.miniMessage()
                                                .deserialize(
                                                        "<red>Only the island owner can reset this island!</red>"));
                                    case RecycleResult.InvalidChallenge ic ->
                                        player.sendMessage(MiniMessage.miniMessage()
                                                .deserialize(
                                                        "<red>Reset confirmation failed: " + ic.reason() + "</red>"));
                                    case RecycleResult.IslandNotFound nf ->
                                        player.sendMessage(
                                                MiniMessage.miniMessage().deserialize("<red>Island not found.</red>"));
                                    case RecycleResult.Failure f ->
                                        player.sendMessage(MiniMessage.miniMessage()
                                                .deserialize("<red>Reset failed: " + f.reason() + "</red>"));
                                }
                            }),
                            () -> {
                                recycleService.cancelResetChallenge(profileId);
                                player.sendMessage(MiniMessage.miniMessage()
                                        .deserialize("<yellow>Island reset cancelled.</yellow>"));
                            });
                    return;
                }
                SimpleGui gui = buildGui(player, profileId, islandId, verificationCode);
                gui.open(player);
            });
        });
    }

    public SimpleGui buildGui(Player player, ProfileId profileId, IslandId islandId, String verificationCode) {
        SimpleGui gui = Guis.gui()
                .title(Component.text("Confirm Island Reset", NamedTextColor.DARK_RED, TextDecoration.BOLD))
                .rows(3)
                .build();

        // Filler
        ItemStack filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE)
                .name(Component.text(" ", NamedTextColor.GRAY))
                .build();
        gui.filler().fill(GuiItem.display(filler));

        // Info icon in center
        ItemStack infoItem = ItemBuilder.of(Material.BARRIER)
                .name(Component.text("ISLAND RESET", NamedTextColor.GOLD, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Confirmation Code: ", NamedTextColor.GRAY)
                                .append(Component.text(verificationCode, NamedTextColor.YELLOW, TextDecoration.BOLD)),
                        Component.text("This action is permanent!", NamedTextColor.RED),
                        Component.text("Click CANCEL to abort.", NamedTextColor.DARK_GRAY)))
                .build();
        gui.set(13, GuiItem.display(infoItem));

        // Confirm button
        ItemStack confirmItem = ItemBuilder.of(Material.RED_CONCRETE)
                .name(Component.text("CONFIRM RESET", NamedTextColor.RED, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("WARNING: CANNOT BE UNDONE!", NamedTextColor.DARK_RED, TextDecoration.BOLD),
                        Component.text("All island blocks, chests, items, and bank funds", NamedTextColor.GRAY),
                        Component.text("will be permanently wiped and recycled.", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Click to permanently reset your island.", NamedTextColor.YELLOW)))
                .build();

        gui.set(11, GuiItem.button(confirmItem, event -> {
            player.closeInventory();
            schedulerPort.onEntity(player.getUniqueId(), () -> {
                RecycleResult result = recycleService.executeReset(profileId, islandId, verificationCode, false);
                switch (result) {
                    case RecycleResult.Success s -> {
                        player.sendMessage(
                                MiniMessage.miniMessage()
                                        .deserialize(
                                                "<green><bold>Your island has been reset and recycled successfully!</bold></green>"));
                        player.sendMessage(MiniMessage.miniMessage()
                                .deserialize("<gray>Create a new island with <yellow>/is create</yellow>.</gray>"));
                    }
                    case RecycleResult.NotOwner no ->
                        player.sendMessage(MiniMessage.miniMessage()
                                .deserialize("<red>Only the island owner can reset this island!</red>"));
                    case RecycleResult.InvalidChallenge ic ->
                        player.sendMessage(MiniMessage.miniMessage()
                                .deserialize("<red>Reset confirmation failed: " + ic.reason() + "</red>"));
                    case RecycleResult.IslandNotFound nf ->
                        player.sendMessage(MiniMessage.miniMessage().deserialize("<red>Island not found.</red>"));
                    case RecycleResult.Failure f ->
                        player.sendMessage(
                                MiniMessage.miniMessage().deserialize("<red>Reset failed: " + f.reason() + "</red>"));
                }
            });
        }));

        // Cancel button
        ItemStack cancelItem = ItemBuilder.of(Material.GREEN_CONCRETE)
                .name(Component.text("CANCEL", NamedTextColor.GREEN, TextDecoration.BOLD))
                .lore(List.of(
                        Component.text("Abort island reset.", NamedTextColor.GRAY),
                        Component.text("Your island will remain safe.", NamedTextColor.GRAY)))
                .build();

        gui.set(15, GuiItem.button(cancelItem, event -> {
            player.closeInventory();
            recycleService.cancelResetChallenge(profileId);
            player.sendMessage(MiniMessage.miniMessage().deserialize("<yellow>Island reset cancelled.</yellow>"));
        }));

        return gui;
    }
}
