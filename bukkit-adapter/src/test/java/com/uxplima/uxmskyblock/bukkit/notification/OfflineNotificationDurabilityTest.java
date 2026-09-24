package com.uxplima.uxmskyblock.bukkit.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.notification.NotificationService;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.notification.NotificationCategory;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.notification.SqlNotificationAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A notice left for a player who is away survives the node restarting and is read out once, when
 * their profile's session is made.
 *
 * <p>The testing standard names this test. A notice is written to SQL, the database is closed the
 * way a stopped server closes it, and a fresh service over the same file delivers it at login. A
 * second login, and a second restart, say nothing more.
 */
class OfflineNotificationDurabilityTest extends MockBukkitHarness {

    @TempDir
    Path dir;

    @Test
    @DisplayName("A notice written before a restart is read out at the next login, and only once")
    void aNoticeSurvivesARestartAndIsReadOnce() throws Exception {
        Path file = dir.resolve("skyblock.db");
        PlayerMock player = createPlayer("Away");
        ProfileId profile = ProfileId.of(UUID.randomUUID());

        try (Database before = open(file)) {
            new MigrationRunner(before).apply(SkyblockMigrations.getMigrations(before.dialect()));
            seedProfile(before, player.getUniqueId(), profile);
            new NotificationService(new SqlNotificationAdapter(before.dataSource()))
                    .notify(profile, NotificationCategory.KICK, "notification.kicked", Map.of("player", "Ayse"), null);
        }

        try (Database after = open(file)) {
            IslandNotificationListener listener = listenerOver(after, player, profile);

            listener.onSessionActive(player);
            List<String> first = heardBy(player);
            assertThat(first).anyMatch(line -> line.contains("You were removed from Ayse's island"));

            listener.onSessionActive(player);
            assertThat(heardBy(player)).describedAs("a second login").isEmpty();
        }

        try (Database again = open(file)) {
            listenerOver(again, player, profile).onSessionActive(player);
            assertThat(heardBy(player))
                    .describedAs("a login after one more restart")
                    .isEmpty();
        }
    }

    private static Database open(Path file) {
        return Database.builder().sqlite(file).build();
    }

    private static IslandNotificationListener listenerOver(Database database, PlayerMock player, ProfileId profile) {
        PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profile));
        return new IslandNotificationListener(
                new NotificationService(new SqlNotificationAdapter(database.dataSource())),
                new InlineSchedulerPort(),
                Messages.bundled(),
                sessions);
    }

    private static void seedProfile(Database database, UUID player, ProfileId profile) throws Exception {
        try (Connection conn = database.connection()) {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO player_accounts (player_uuid) VALUES (?)")) {
                ps.setString(1, player.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps =
                    conn.prepareStatement("INSERT INTO player_profiles (profile_id, player_uuid) VALUES (?, ?)")) {
                ps.setString(1, profile.value().toString());
                ps.setString(2, player.toString());
                ps.executeUpdate();
            }
        }
    }

    private static List<String> heardBy(PlayerMock player) {
        List<String> heard = new ArrayList<>();
        for (Component line = player.nextComponentMessage(); line != null; line = player.nextComponentMessage()) {
            heard.add(PlainTextComponentSerializer.plainText().serialize(line));
        }
        return heard;
    }
}
