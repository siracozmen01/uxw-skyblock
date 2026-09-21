package com.uxplima.uxmskyblock.bukkit.recycle;

import java.util.Objects;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService.RecycleResult;

/**
 * What a player is told when a reset finishes, in one place.
 *
 * <p>The same five outcomes were answered in three places: the command, the chest button of the
 * confirmation menu, and its Bedrock form. Three copies of one switch is three chances for a later
 * case to be handled in two of them.
 */
public final class ResetAnnouncer {

    private final Messages messages;

    public ResetAnnouncer(Messages messages) {
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
    }

    /** Tells {@code viewer} what the reset did, in the language they read. */
    public void announce(Audience viewer, RecycleResult result) {
        Objects.requireNonNull(viewer, "viewer must not be null");
        Objects.requireNonNull(result, "result must not be null");
        switch (result) {
            case RecycleResult.Success success -> {
                messages.send(viewer, "reset.success");
                messages.send(viewer, "reset.success_hint");
            }
            case RecycleResult.NotOwner notOwner -> messages.send(viewer, "reset.not_owner");
            case RecycleResult.InvalidChallenge invalid ->
                messages.send(viewer, "reset.invalid_challenge", Placeholder.unparsed("reason", invalid.reason()));
            case RecycleResult.IslandNotFound notFound -> messages.send(viewer, "reset.island_not_found");
            case RecycleResult.Failure failure ->
                messages.send(viewer, "reset.failed", Placeholder.unparsed("reason", failure.reason()));
        }
    }
}
