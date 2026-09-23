package com.uxplima.uxmskyblock.bukkit.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.message.MessagePayload;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import org.jspecify.annotations.Nullable;

/**
 * Hands a player what was left for them while they were away.
 *
 * <p>The notification table and its service have been here since the enterprise foundation work,
 * with a drain that marks everything read, and nothing ever called it. A message nobody delivers is
 * a row in a table.
 */
public final class IslandNotificationListener implements Listener {

    private final NotificationService notificationService;
    private final SchedulerPort schedulerPort;
    private final Messages messages;
    private final @Nullable PlayerSessionCoordinator sessionCoordinator;

    public IslandNotificationListener(
            NotificationService notificationService,
            SchedulerPort schedulerPort,
            Messages messages,
            @Nullable PlayerSessionCoordinator sessionCoordinator) {
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService must not be null");
        this.schedulerPort = Objects.requireNonNull(schedulerPort, "schedulerPort must not be null");
        this.messages = Objects.requireNonNull(messages, "messages must not be null");
        this.sessionCoordinator = sessionCoordinator;
    }

    /**
     * Reads out what waited for a player once their session is active.
     *
     * <p>It used to run on the join event, and a session is made off the join thread after it, so
     * at join the player had no profile yet and every notice written while they were away stayed
     * unread. The session coordinator calls this when the session is made.
     */
    public void onSessionActive(Player player) {
        deliverTo(player);
    }

    /**
     * Drains and sends. The read happens off the entity thread because it writes: the drain marks
     * everything it returns as read, and a write on the thread a join arrives on is the defect this
     * plugin's own standards name first.
     */
    public void deliverTo(Player player) {
        Optional<ProfileId> optProfile = activeProfile(player);
        if (optProfile.isEmpty()) {
            return;
        }
        ProfileId profileId = optProfile.get();
        PlayerUuid playerUuid = new PlayerUuid(player.getUniqueId());

        schedulerPort.async(() -> {
            List<Notification> pending = notificationService.pendingNotifications(profileId);
            if (pending.isEmpty()) {
                return;
            }
            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    // Gone before the notices reached them: they stay unread for the next login.
                    return;
                }
                messages.send(
                        player, "notification.header", Placeholder.unparsed("count", Integer.toString(pending.size())));
                for (Notification notification : pending) {
                    sendOne(player, notification);
                }
                // Only what was shown is marked read, and only once it was.
                schedulerPort.async(() -> notificationService.markDelivered(pending, java.time.Instant.now()));
            });
        });
    }

    /**
     * Writes one notice out in the reader's own language.
     *
     * <p>What was stored is the name of a message and the values that message has holes for, never
     * the sentence: a player who reads Turkish must not be told in English what happened to their
     * island while they were away, and an operator who rewrites the wording must not find the old
     * wording still sitting in a table.
     *
     * <p>A row naming a message the catalogue does not have falls back to the plain entry line, so
     * a notice written before a language file was edited still reaches the player it belongs to.
     */
    private void sendOne(Player player, Notification notification) {
        String key = notification.payloadTypeId();
        Map<String, String> values = MessagePayload.unpack(notification.payloadData());
        if (messages.has(key)) {
            List<TagResolver> resolvers = new ArrayList<>(values.size());
            for (Map.Entry<String, String> value : values.entrySet()) {
                resolvers.add(Placeholder.unparsed(value.getKey(), value.getValue()));
            }
            messages.send(player, key, resolvers.toArray(new TagResolver[0]));
            return;
        }
        messages.send(
                player,
                "notification.entry",
                Placeholder.unparsed("category", notification.category().name()),
                Placeholder.unparsed("body", values.getOrDefault("body", notification.payloadData())));
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }
}
