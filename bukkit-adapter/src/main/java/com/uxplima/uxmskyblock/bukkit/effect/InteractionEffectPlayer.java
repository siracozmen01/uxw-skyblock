package com.uxplima.uxmskyblock.bukkit.effect;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.effect.InteractionEffect;
import com.uxplima.uxmskyblock.core.domain.effect.InteractionEffects;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import org.jspecify.annotations.Nullable;

/**
 * Fires what the operator wrote for an interaction.
 *
 * <p>Every interaction fires any number of messages, titles, subtitles, action bars, boss bars,
 * sounds and particles, in the order the file lists them. Four of them had a sound written into
 * Java instead, and two of those a particle as well, so an operator who wanted a different note or
 * none at all had nowhere to say so.
 *
 * <p>A line the server cannot make sense of, a sound this version does not have or a particle that
 * was renamed, is skipped. One mistyped line must not stop the rest of them, and it must never stop
 * the thing the interaction was about.
 */
public final class InteractionEffectPlayer {

    private final Messages messages;
    private final @Nullable SchedulerPort schedulerPort;

    public InteractionEffectPlayer(Messages messages) {
        this(messages, null);
    }

    public InteractionEffectPlayer(Messages messages, @Nullable SchedulerPort schedulerPort) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.schedulerPort = schedulerPort;
    }

    /**
     * Fires one interaction for a player standing somewhere.
     *
     * <p>Everything here touches the player or their world, so the caller is expected to be on the
     * thread that owns them, which is where all four callers already are.
     */
    public void fire(
            InteractionEffects effects, String interaction, Player player, Location where, TagResolver... resolvers) {
        Objects.requireNonNull(effects, "effects must not be null");
        Objects.requireNonNull(interaction, "interaction must not be null");
        Objects.requireNonNull(player, "player must not be null");

        for (InteractionEffect effect : effects.of(interaction)) {
            try {
                apply(effect, player, where, resolvers);
            } catch (RuntimeException skipped) {
                // A name this version does not have. The rest of the list still happens.
            }
        }
    }

    /** Fires one interaction at a place with nobody in particular to tell, so only what a world can do. */
    public void fireAt(InteractionEffects effects, String interaction, Location where) {
        Objects.requireNonNull(effects, "effects must not be null");
        Objects.requireNonNull(interaction, "interaction must not be null");
        Objects.requireNonNull(where, "where must not be null");

        World world = where.getWorld();
        if (world == null) {
            return;
        }
        for (InteractionEffect effect : effects.of(interaction)) {
            try {
                switch (effect.kind()) {
                    case SOUND ->
                        world.playSound(where, soundNamed(effect.name()), (float) effect.number(0, 1.0), (float)
                                effect.number(1, 1.0));
                    case PARTICLE ->
                        world.spawnParticle(
                                particleNamed(effect.name()),
                                where,
                                (int) effect.number(0, 1),
                                effect.number(1, 0.0),
                                effect.number(2, 0.0),
                                effect.number(3, 0.0),
                                effect.number(4, 0.0));
                    default -> {
                        // A place has nobody to read a message, a title or a bar.
                    }
                }
            } catch (RuntimeException skipped) {
                // As above: one line that cannot be made sense of is one line.
            }
        }
    }

    private void apply(InteractionEffect effect, Player player, @Nullable Location where, TagResolver... resolvers) {
        Location at = where != null ? where : player.getLocation();
        switch (effect.kind()) {
            case MESSAGE -> player.sendMessage(render(player, effect.name(), resolvers));
            case TITLE -> player.showTitle(Title.title(render(player, effect.name(), resolvers), Component.empty()));
            case SUBTITLE -> player.showTitle(Title.title(Component.empty(), render(player, effect.name(), resolvers)));
            case ACTION_BAR -> player.sendActionBar(render(player, effect.name(), resolvers));
            case BOSS_BAR -> showBar(effect, player, resolvers);
            case SOUND -> {
                if (at != null) {
                    player.playSound(at, soundNamed(effect.name()), (float) effect.number(0, 1.0), (float)
                            effect.number(1, 1.0));
                }
            }
            case PARTICLE -> {
                if (at != null) {
                    player.spawnParticle(
                            particleNamed(effect.name()),
                            at,
                            (int) effect.number(0, 1),
                            effect.number(1, 0.0),
                            effect.number(2, 0.0),
                            effect.number(3, 0.0),
                            effect.number(4, 0.0));
                }
            }
        }
    }

    /**
     * A bar across the top, for as many seconds as the line says.
     *
     * <p>Taking it away again needs a clock. A node built without one shows the bar and leaves it,
     * which is worse than not showing it, so a node without a scheduler shows nothing.
     */
    private void showBar(InteractionEffect effect, Player player, TagResolver... resolvers) {
        SchedulerPort scheduler = this.schedulerPort;
        if (scheduler == null) {
            return;
        }
        String[] words = effect.words();
        BossBar.Color colour = BossBar.Color.WHITE;
        if (words.length > 1) {
            try {
                colour = BossBar.Color.valueOf(words[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknownColour) {
                colour = BossBar.Color.WHITE;
            }
        }
        BossBar bar = BossBar.bossBar(render(player, effect.name(), resolvers), 1.0f, colour, BossBar.Overlay.PROGRESS);
        player.showBossBar(bar);
        long seconds = (long) Math.max(1, effect.number(1, 5));
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());
        scheduler.asyncAfter(
                Duration.ofSeconds(seconds), () -> scheduler.onEntity(playerUuid, () -> player.hideBossBar(bar)));
    }

    private Component render(Player player, String key, TagResolver... resolvers) {
        return messages.render(player, key, resolvers);
    }

    private static Sound soundNamed(String name) {
        return Registry.SOUNDS.getOrThrow(
                org.bukkit.NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT).replace('_', '.')));
    }

    private static Particle particleNamed(String name) {
        return Particle.valueOf(name.toUpperCase(Locale.ROOT));
    }
}
