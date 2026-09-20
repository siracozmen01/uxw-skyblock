package com.uxplima.uxmskyblock.bukkit.bedrock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.bukkit.entity.Player;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockDetector;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmlib.bedrock.BedrockWidget;
import com.uxplima.uxmskyblock.bukkit.i18n.MessageProvider;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandFlags;
import com.uxplima.uxmskyblock.core.domain.warp.IslandWarp;

/**
 * Enterprise service presenting native Bedrock Forms (SimpleForm, ModalForm, CustomForm)
 * to Bedrock/Floodgate players without disruption to Java players (Section 2.16).
 */
public final class BedrockFormService {

    private final BedrockDetector detector;
    private final BedrockScreen screen;
    private final MessageProvider messageProvider;

    public BedrockFormService(BedrockDetector detector, BedrockScreen screen) {
        this(detector, screen, new MessageProvider("en"));
    }

    public BedrockFormService(BedrockDetector detector, BedrockScreen screen, MessageProvider messageProvider) {
        this.detector = Objects.requireNonNull(detector, "detector must not be null");
        this.screen = Objects.requireNonNull(screen, "screen must not be null");
        this.messageProvider = Objects.requireNonNull(messageProvider, "messageProvider must not be null");
    }

    public MessageProvider messageProvider() {
        return messageProvider;
    }

    @SuppressWarnings("EmptyCatch")
    private String resolveLocale(Player player) {
        if (player == null) {
            return messageProvider.defaultLocale();
        }
        try {
            if (player.locale() != null) {
                return player.locale().getLanguage();
            }
        } catch (Throwable ignored) {
        }
        return messageProvider.defaultLocale();
    }

    @SuppressWarnings("EmptyCatch")
    private String text(String key, String locale, String fallback) {
        String val = messageProvider.getRaw(key, locale);
        if (val == null || val.equals(key)) {
            return fallback;
        }
        try {
            Component comp = messageProvider.getComponentWithoutPrefix(key, locale);
            return LegacyComponentSerializer.legacySection().serialize(comp);
        } catch (Throwable ignored) {
            return val;
        }
    }

    public boolean isBedrock(Player player) {
        return player != null && detector.isBedrock(player.getUniqueId());
    }

    public boolean isBedrock(UUID uuid) {
        return uuid != null && detector.isBedrock(uuid);
    }

    /**
     * Sends a SimpleForm for Island Control to a Bedrock player.
     */
    public void openIslandControlForm(
            Player player,
            Island island,
            Runnable onHome,
            Runnable onWarps,
            Runnable onBank,
            Runnable onMembers,
            Runnable onSettings) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(island, "island must not be null");

        String locale = resolveLocale(player);
        List<BedrockButton> buttons = List.of(
                new BedrockButton(text("bedrock.button_home", locale, "§aIsland Home"), null),
                new BedrockButton(text("bedrock.button_warps", locale, "§bWarps"), null),
                new BedrockButton(text("bedrock.button_bank", locale, "§6Bank Treasury"), null),
                new BedrockButton(text("bedrock.button_members", locale, "§dMembers & Roles"), null),
                new BedrockButton(text("bedrock.button_settings", locale, "§eSettings"), null));

        screen.sendSimpleForm(
                player,
                text("bedrock.control_title", locale, "Island Control Panel"),
                text("bedrock.control_content", locale, "Manage your island settings, treasury, and warps."),
                buttons,
                index -> {
                    switch (index) {
                        case 0 -> {
                            if (onHome != null) {
                                onHome.run();
                            }
                        }
                        case 1 -> {
                            if (onWarps != null) {
                                onWarps.run();
                            }
                        }
                        case 2 -> {
                            if (onBank != null) {
                                onBank.run();
                            }
                        }
                        case 3 -> {
                            if (onMembers != null) {
                                onMembers.run();
                            }
                        }
                        case 4 -> {
                            if (onSettings != null) {
                                onSettings.run();
                            }
                        }
                        default -> {
                            // unknown index
                        }
                    }
                });
    }

    /**
     * Sends a SimpleForm displaying available Island Warps.
     */
    public void openWarpDirectoryForm(Player player, List<IslandWarp> warps, Consumer<IslandWarp> onSelect) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(warps, "warps must not be null");

        String locale = resolveLocale(player);
        List<BedrockButton> buttons = new ArrayList<>();
        for (IslandWarp warp : warps) {
            buttons.add(new BedrockButton(
                    "§b" + warp.name().value() + " §7[" + warp.category().name() + "]", null));
        }

        screen.sendSimpleForm(
                player,
                text("bedrock.warps_title", locale, "Island Warps"),
                text("bedrock.warps_content", locale, "Select a warp destination to teleport:"),
                buttons,
                index -> {
                    if (index >= 0 && index < warps.size() && onSelect != null) {
                        onSelect.accept(warps.get(index));
                    }
                });
    }

    /**
     * Sends a ModalForm for two-button confirmation (e.g. Island Reset/Deletion).
     */
    public void openConfirmationModal(
            Player player,
            String title,
            String content,
            String confirmButton,
            String cancelButton,
            Runnable onConfirm,
            Runnable onCancel) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(title, "title must not be null");

        String locale = resolveLocale(player);
        String defaultConfirm = text("bedrock.modal_confirm", locale, "§aConfirm");
        String defaultCancel = text("bedrock.modal_cancel", locale, "§cCancel");
        screen.sendModalForm(
                player,
                title,
                content,
                confirmButton != null ? confirmButton : defaultConfirm,
                cancelButton != null ? cancelButton : defaultCancel,
                onConfirm != null ? onConfirm : () -> {},
                onCancel != null ? onCancel : () -> {});
    }

    /**
     * Sends a CustomForm for Island Settings with toggles.
     */
    public void openIslandSettingsForm(
            Player player, IslandFlags currentFlags, Consumer<IslandFlags> onSave, Runnable onClose) {
        Objects.requireNonNull(player, "player must not be null");
        Objects.requireNonNull(currentFlags, "currentFlags must not be null");

        String locale = resolveLocale(player);
        List<BedrockWidget> widgets = List.of(
                new BedrockWidget.Label(text(
                        "bedrock.settings_label", locale, "Configure island protection and environmental policies:")),
                new BedrockWidget.Toggle(
                        "PVP", text("bedrock.flag_pvp", locale, "PvP Combat"), currentFlags.isEnabled(IslandFlags.PVP)),
                new BedrockWidget.Toggle(
                        "FIRE_SPREAD",
                        text("bedrock.flag_fire_spread", locale, "Fire Spread"),
                        currentFlags.isEnabled(IslandFlags.FIRE_SPREAD)),
                new BedrockWidget.Toggle(
                        "MONSTER_SPAWN",
                        text("bedrock.flag_monster_spawn", locale, "Monster Spawning"),
                        currentFlags.isEnabled(IslandFlags.MONSTER_SPAWN)),
                new BedrockWidget.Toggle(
                        "ANIMAL_SPAWN",
                        text("bedrock.flag_animal_spawn", locale, "Animal Spawning"),
                        currentFlags.isEnabled(IslandFlags.ANIMAL_SPAWN)),
                new BedrockWidget.Toggle(
                        "VISITOR_ACCESS",
                        text("bedrock.flag_visitor_access", locale, "Visitor Access"),
                        currentFlags.isEnabled(IslandFlags.VISITOR_ACCESS)),
                new BedrockWidget.Toggle(
                        "EXPLOSION_DAMAGE",
                        text("bedrock.flag_explosion_damage", locale, "Explosion Damage"),
                        currentFlags.isEnabled(IslandFlags.EXPLOSION_DAMAGE)));

        screen.sendCustomForm(
                player,
                text("bedrock.settings_title", locale, "Island Settings"),
                null,
                widgets,
                submittedMap -> {
                    if (onSave != null) {
                        IslandFlags updated = currentFlags;
                        for (Map.Entry<String, String> entry : submittedMap.entrySet()) {
                            updated = updated.withFlag(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
                        }
                        onSave.accept(updated);
                    }
                },
                onClose != null ? onClose : () -> {});
    }

    public BedrockDetector detector() {
        return detector;
    }

    public BedrockScreen screen() {
        return screen;
    }
}
