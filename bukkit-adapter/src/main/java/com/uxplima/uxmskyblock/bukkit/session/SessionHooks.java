package com.uxplima.uxmskyblock.bukkit.session;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.entity.Player;

import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;

/**
 * What the rest of the plugin asked to hear about a session: that one was made, and that a player
 * switched away from a profile. A hook that throws is reported and the others still run.
 */
final class SessionHooks {

    private static final Logger LOGGER = Logger.getLogger(SessionHooks.class.getName());

    /** What runs on the player's thread once their session is made and their inventory applied. */
    private final List<Consumer<Player>> whenActive = new CopyOnWriteArrayList<>();

    /**
     * Runs {@code hook} for each player whose session is made, on the player's own thread.
     *
     * <p>A session is made off the join thread, after the join event, so anything that needs the
     * player's profile cannot ask for it at join.
     */
    void whenActive(Consumer<Player> hook) {
        whenActive.add(Objects.requireNonNull(hook, "hook"));
    }

    /** What runs on the player's thread when they switch away from a profile, with that profile. */
    private final List<BiConsumer<Player, ProfileId>> whenLeft = new CopyOnWriteArrayList<>();

    /**
     * Runs {@code hook} when a player switches away from a profile, before the session hooks run for
     * the one they switched to. A switch is a leave and an arrival: anything that tracks who is
     * playing by profile has to hear both, or it keeps the old profile and misses the new one.
     */
    void whenLeft(BiConsumer<Player, ProfileId> hook) {
        whenLeft.add(Objects.requireNonNull(hook, "hook"));
    }

    void runActive(Player player) {
        for (Consumer<Player> hook : whenActive) {
            try {
                hook.accept(player);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A hook on a new session failed for " + player.getName(), e);
            }
        }
    }

    void runLeft(Player player, ProfileId left) {
        for (BiConsumer<Player, ProfileId> hook : whenLeft) {
            try {
                hook.accept(player, left);
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "A hook on a profile switch failed for " + player.getName(), e);
            }
        }
    }
}
