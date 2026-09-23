package com.uxplima.uxmskyblock.bukkit.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.application.notification.NotificationStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.message.MessagePayload;
import com.uxplima.uxmskyblock.core.domain.notification.Notification;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * What happened while you were away is written down and read back to you in your own language.
 *
 * <p>The inbox, its table, its ten categories and the delivery on join have been here since the
 * notification work, and nothing ever wrote a row: dispatchNotification had no caller anywhere, so
 * "while you were away" was always empty.
 */
class TheInboxIsWrittenToTest {

    private ServerMock server;
    private PlayerMock player;
    private ProfileId profileId;
    private InMemoryNotifications storage;
    private NotificationService service;
    private IslandNotificationListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer("Guest");
        profileId = new ProfileId(player.getUniqueId());

        storage = new InMemoryNotifications();
        service = new NotificationService(storage);

        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(invocation -> {
                    invocation.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));

        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));

        listener = new IslandNotificationListener(service, scheduler, Messages.bundled(), sessions);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    @DisplayName("A notice names a message and its values, never a sentence")
    void anoticeNamesAMessage() {
        Notification written = service.notify(
                profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);

        assertThat(written.payloadTypeId()).isEqualTo("notification.kicked");
        assertThat(written.payloadData())
                .describedAs("the values, and not one word a player reads")
                .doesNotContain("removed")
                .doesNotContain("island");
        assertThat(MessagePayload.unpack(written.payloadData())).containsEntry("player", "Owner");
    }

    @Test
    @DisplayName("The notice is read back with its values filled in")
    void thenoticeIsReadBackFilledIn() {
        service.notify(profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);

        listener.deliverTo(player);

        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage())
                .describedAs("the sentence comes out of the catalogue with the name filled in")
                .contains("Owner")
                .contains("removed");
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("A notice naming a message the catalogue does not have still reaches the player")
    void anunknownMessageStillReaches() {
        service.dispatchNotification(
                profileId, NotificationCategory.SYSTEM, "uxm:something_older", 1, "the server restarted", null);

        listener.deliverTo(player);

        assertThat(player.nextMessage()).describedAs("the header").isNotNull();
        assertThat(player.nextMessage())
                .describedAs("the plain entry line, carrying what was stored")
                .contains("the server restarted");
    }

    @Test
    @DisplayName("A player who left before the notices reached them finds them unread next time")
    void aNoticeNotShownStaysUnread() {
        service.notify(profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);
        SchedulerPort leftAlready = mock(SchedulerPort.class);
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(leftAlready)
                .async(any(Runnable.class));
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));
        // The player's own thread never gets to it: they have gone.
        new IslandNotificationListener(service, leftAlready, Messages.bundled(), sessions).deliverTo(player);

        assertThat(service.getUnreadCount(profileId))
                .describedAs("the notice nobody read")
                .isEqualTo(1);
        listener.deliverTo(player);
        assertThat(player.nextMessage())
                .describedAs("read out at the next chance")
                .isNotNull();
    }

    @Test
    @DisplayName("A session made after the join reads out what waited, through the coordinator's hook")
    void theSessionHookReadsTheNotices() {
        service.notify(profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);

        listener.onSessionActive(player);

        assertThat(player.nextMessage()).isNotNull();
    }

    @Test
    @DisplayName("Reading a notice marks it read, so it is not read out twice")
    void anoticeIsOnlyReadOnce() {
        service.notify(profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);

        listener.deliverTo(player);
        assertThat(player.nextMessage()).isNotNull();
        assertThat(player.nextMessage()).isNotNull();

        listener.deliverTo(player);
        assertThat(player.nextMessage()).describedAs("nothing is left to say").isNull();
    }

    @Test
    @DisplayName("A notice already read and old enough is deleted")
    void areadNoticeIsEventuallyDeleted() {
        service.notify(profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);
        listener.deliverTo(player);
        assertThat(storage.saved).hasSize(1);

        assertThat(service.purgeRead(Instant.now().plusSeconds(1)))
                .describedAs("read, and older than the cutoff")
                .isEqualTo(1);
        assertThat(storage.saved).isEmpty();
    }

    @Test
    @DisplayName("A notice nobody has read yet is never deleted")
    void anUnreadNoticeIsKept() {
        service.notify(profileId, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Owner"), null);

        assertThat(service.purgeRead(Instant.now().plusSeconds(1))).isZero();
        assertThat(storage.saved).hasSize(1);
    }

    /** The smallest inbox that behaves like the real one. */
    private static final class InMemoryNotifications implements NotificationStoragePort {

        final List<Notification> saved = new ArrayList<>();

        @Override
        public void saveNotification(Notification notification) {
            saved.add(notification);
        }

        @Override
        public List<Notification> findPendingNotifications(ProfileId recipientProfileId) {
            return saved.stream()
                    .filter(notification -> notification.recipientProfileId().equals(recipientProfileId))
                    .filter(notification -> !notification.isRead())
                    .toList();
        }

        @Override
        public void markAsRead(UUID notificationId, Instant readAt) {
            for (int i = 0; i < saved.size(); i++) {
                if (saved.get(i).notificationId().equals(notificationId)) {
                    saved.set(i, saved.get(i).markAsRead(readAt));
                }
            }
        }

        @Override
        public void markAllAsRead(ProfileId recipientProfileId, Instant readAt) {
            for (int i = 0; i < saved.size(); i++) {
                if (saved.get(i).recipientProfileId().equals(recipientProfileId)) {
                    saved.set(i, saved.get(i).markAsRead(readAt));
                }
            }
        }

        @Override
        public int countUnread(ProfileId recipientProfileId) {
            return findPendingNotifications(recipientProfileId).size();
        }

        @Override
        public int purgeReadBefore(Instant before) {
            int held = saved.size();
            saved.removeIf(notification -> notification.isRead()
                    && notification.readAt() != null
                    && notification.readAt().isBefore(before));
            return held - saved.size();
        }
    }
}
