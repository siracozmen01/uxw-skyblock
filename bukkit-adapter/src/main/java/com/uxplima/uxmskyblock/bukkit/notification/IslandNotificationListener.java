package com.uxplima.uxmskyblock.bukkit.notification;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
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
            List<Notification> pending = notificationService.drainPendingNotifications(profileId);
            if (pending.isEmpty()) {
                return;
            }
            schedulerPort.onEntity(playerUuid, () -> {
                if (!player.isOnline()) {
                    return;
                }
                messages.send(
                        player, "notification.header", Placeholder.unparsed("count", Integer.toString(pending.size())));
                for (Notification notification : pending) {
                    messages.send(
                            player,
                            "notification.entry",
                            Placeholder.unparsed(
                                    "category", notification.category().name()),
                            Placeholder.unparsed("body", notification.payloadData()));
                }
            });
        });
    }

    private Optional<ProfileId> activeProfile(Player player) {
        if (sessionCoordinator == null) {
            return Optional.empty();
        }
        return sessionCoordinator.activeProfile(player.getUniqueId());
    }
}
