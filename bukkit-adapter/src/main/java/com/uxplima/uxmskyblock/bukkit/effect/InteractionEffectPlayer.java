package com.uxplima.uxmskyblock.bukkit.effect;

import java.util.Objects;

import org.bukkit.entity.Player;

import com.uxplima.uxmlib.condition.ItemStore;
import com.uxplima.uxmlib.condition.OperandResolver;
import com.uxplima.uxmlib.condition.action.ActionContext;
import com.uxplima.uxmlib.condition.action.ActionCostException;
import com.uxplima.uxmlib.condition.action.ActionList;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import org.jspecify.annotations.Nullable;

/**
 * Fires what the operator wrote for an interaction, through the family's action engine.
 *
 * <p>The context is wired all the way rather than partly. A player is the subject and the target
 * audience, so {@code [message]}, {@code [title]}, {@code [actionbar]} and {@code [sound]} have
 * somebody to reach: a context without one renders every line and drops it, quietly. The item store
 * is the player's own inventory, because the engine's default answers "none" to every item question
 * and a {@code [take-item]} against it refuses, quietly and wrongly.
 *
 * <p>{@code [bossbar]} needs a delay to take its bar down again. A node with a scheduler wires one.
 * A node without hears about it the first time such a line runs, which is the engine's own way of
 * saying so and better than a bar that never goes away.
 */
public final class InteractionEffectPlayer {

    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(InteractionEffectPlayer.class.getName());

    private final @Nullable SchedulerPort schedulerPort;

    public InteractionEffectPlayer() {
        this(null);
    }

    public InteractionEffectPlayer(@Nullable SchedulerPort schedulerPort) {
        this.schedulerPort = schedulerPort;
    }

    /**
     * Fires one interaction for one player.
     *
     * <p>Everything here reaches the player or their world, so the caller is expected to be on the
     * thread that owns them, which is where all four callers already are.
     */
    public void fire(InteractionEffects effects, String interaction, Player player) {
        Objects.requireNonNull(effects, "effects must not be null");
        Objects.requireNonNull(interaction, "interaction must not be null");
        Objects.requireNonNull(player, "player must not be null");

        ActionList list = effects.of(interaction);
        if (list.actions().isEmpty()) {
            return;
        }
        try {
            list.run(contextFor(player));
        } catch (ActionCostException unpaid) {
            // A price nobody can pay is the operator's own gate doing its job. The engine charges
            // every cost before it runs anything, so nothing has happened and nobody is half
            // charged. It is said quietly rather than thrown, because the thing this interaction
            // was about has already happened and must not be undone by a cosmetic line.
            LOGGER.fine(() -> "The " + interaction + " list asked " + player.getName()
                    + " for something they could not pay, so none of it ran: " + unpaid.getMessage());
        } catch (RuntimeException failed) {
            // One line that cannot be carried out is one line. The interaction still happened.
            LOGGER.warning(() -> "The " + interaction + " list could not be carried out for " + player.getName() + ": "
                    + failed.getMessage());
        }
    }

    /** The context an action reads: who it is about, who hears it, and what they are holding. */
    private ActionContext contextFor(Player player) {
        ActionContext.Builder builder = ActionContext.builder(OperandResolver.identity())
                // The subject, which the engine also makes the target audience.
                .player(player)
                // An island's own interaction reaches the player it happened to. Nothing here is
                // server wide, and an audience left empty renders every line and drops it.
                .broadcast(player)
                .itemStore(ItemStore.inventory());
        SchedulerPort scheduler = this.schedulerPort;
        if (scheduler != null) {
            builder.later(scheduler::asyncAfter);
        }
        return builder.build();
    }
}
