package com.uxplima.uxmskyblock.bukkit.poseidon;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.uxplima.uxmlib.bedrock.BedrockButton;
import com.uxplima.uxmlib.bedrock.BedrockScreen;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * The lore of the sea, which guides a Poseidon island's players: a written book on Java, and a form a
 * page at a time on Bedrock. Every page is a line of {@code poseidon.lore.pages} in the reader's
 * language file, so the operator writes as many as they like, in every language they ship.
 */
public final class PoseidonLore {

    private static final Logger LOGGER = Logger.getLogger(PoseidonLore.class.getName());

    private final Messages messages;
    private final SchedulerPort scheduler;
    private final Function<UUID, Optional<ProfileId>> activeProfile;
    private final Function<ProfileId, GameModeType> modeOf;
    private final Predicate<Player> isBedrock;
    private final BedrockScreen screen;
    private final BiConsumer<Player, Book> openBook;

    /**
     * @param modeOf the mode a profile's island plays, which may read the database
     * @param openBook opens a written book for a Java player
     */
    public PoseidonLore(
            Messages messages,
            SchedulerPort scheduler,
            Function<UUID, Optional<ProfileId>> activeProfile,
            Function<ProfileId, GameModeType> modeOf,
            Predicate<Player> isBedrock,
            BedrockScreen screen,
            BiConsumer<Player, Book> openBook) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.activeProfile = Objects.requireNonNull(activeProfile, "activeProfile must not be null");
        this.modeOf = Objects.requireNonNull(modeOf, "modeOf must not be null");
        this.isBedrock = Objects.requireNonNull(isBedrock, "isBedrock must not be null");
        this.screen = Objects.requireNonNull(screen, "screen must not be null");
        this.openBook = Objects.requireNonNull(openBook, "openBook must not be null");
    }

    /** Opens the lore for a player whose island is a Poseidon island, or says why it does not. */
    public void open(Player player) {
        UUID id = player.getUniqueId();
        scheduler.async(() -> {
            boolean poseidon;
            try {
                poseidon = activeProfile
                        .apply(id)
                        .map(profile -> modeOf.apply(profile) == GameModeType.POSEIDON)
                        .orElse(false);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, e, () -> "The island mode of " + id + " could not be read.");
                poseidon = false;
            }
            boolean reads = poseidon;
            scheduler.onEntity(PlayerUuid.of(id), () -> {
                if (!player.isOnline()) {
                    return;
                }
                if (!reads) {
                    messages.send(player, "poseidon.not_poseidon");
                } else if (isBedrock.test(player)) {
                    showPage(player, 0);
                } else {
                    showBook(player);
                }
            });
        });
    }

    private List<Component> pages(Player player) {
        return messages.renderAll(player, "poseidon.lore.pages");
    }

    private void showBook(Player player) {
        List<Component> pages = pages(player);
        if (pages.isEmpty()) {
            messages.send(player, "poseidon.lore_empty");
            return;
        }
        openBook.accept(
                player,
                Book.book(
                        messages.renderPlain(player, "poseidon.lore.title"),
                        messages.renderPlain(player, "poseidon.lore.author"),
                        pages));
    }

    /** One page as a Bedrock form, with a button back, a button on while there is more, and one to close. */
    void showPage(Player player, int page) {
        List<Component> pages = pages(player);
        if (pages.isEmpty()) {
            messages.send(player, "poseidon.lore_empty");
            return;
        }
        int shown = Math.clamp(page, 0, pages.size() - 1);
        List<BedrockButton> buttons = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        if (shown > 0) {
            buttons.add(new BedrockButton(text(player, "poseidon.lore.previous"), null));
            actions.add(() -> showPage(player, shown - 1));
        }
        if (shown < pages.size() - 1) {
            buttons.add(new BedrockButton(text(player, "poseidon.lore.next"), null));
            actions.add(() -> showPage(player, shown + 1));
        }
        buttons.add(new BedrockButton(text(player, "poseidon.lore.close"), null));
        actions.add(() -> {});
        screen.sendSimpleForm(player, text(player, "poseidon.lore.title"), legacy(pages.get(shown)), buttons, index -> {
            if (index >= 0 && index < actions.size()) {
                actions.get(index).run();
            }
        });
    }

    /** A catalogue line in the reader's language, flattened for a form. */
    private String text(Player player, String key) {
        return legacy(messages.renderPlain(player, key));
    }

    /** A Floodgate form takes a legacy string, so a rendered line is flattened for it. */
    private static String legacy(Component line) {
        return LegacyComponentSerializer.legacySection().serialize(line);
    }
}
