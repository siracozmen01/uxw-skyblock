package com.uxplima.uxmskyblock.core.application.recycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

import com.uxplima.uxmskyblock.core.application.backup.BackupService;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseBackupPort;
import com.uxplima.uxmskyblock.core.application.backup.DatabaseDisasterBackupService;
import com.uxplima.uxmskyblock.core.application.event.OutboxPort;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.world.SpiralSlotPoolPort;
import com.uxplima.uxmskyblock.core.application.world.WorldGridAllocationPort;
import com.uxplima.uxmskyblock.core.domain.backup.BackupSetId;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A confirm code is four digits a keyboard can type, whatever the server's own language.
 *
 * <p>The code was formatted in the default locale. A server running in Egypt or Iran writes its own
 * digits there, so the admin read a code in Arabic or Persian numerals, typed the ones on their
 * keyboard, and the two strings never matched.
 */
class AConfirmCodeIsTypeableOnAnyServerTest {

    private static final String[] LOCALES_WITH_THEIR_OWN_DIGITS = {"ar-EG", "fa-IR", "th-TH-u-nu-thai"};

    @Test
    @DisplayName("A reset code is four ASCII digits on a server that writes its own")
    void aResetCodeIsAscii() {
        IslandRecycleService service = new IslandRecycleService(
                mock(IslandStoragePort.class),
                mock(WorldGridAllocationPort.class),
                mock(SpiralSlotPoolPort.class),
                null,
                null,
                mock(OutboxPort.class),
                null,
                Clock.fixed(Instant.parse("2026-09-22T12:00:00Z"), ZoneOffset.UTC),
                Duration.ofMinutes(1));

        for (String tag : LOCALES_WITH_THEIR_OWN_DIGITS) {
            String code = underLocale(
                    tag,
                    () -> service.generateResetChallenge(
                                    new ProfileId(UUID.randomUUID()), IslandId.of(UUID.randomUUID()))
                            .code());
            assertThat(code).describedAs("the code issued under " + tag).matches("[0-9]{4}");
        }
    }

    @Test
    @DisplayName("A database restore code is four ASCII digits on a server that writes its own")
    void aRestoreCodeIsAscii() {
        DatabaseDisasterBackupService service =
                new DatabaseDisasterBackupService(mock(BackupService.class), mock(DatabaseBackupPort.class), "1.0.0");

        for (String tag : LOCALES_WITH_THEIR_OWN_DIGITS) {
            DatabaseDisasterBackupService.RestoreOutcome outcome = underLocale(
                    tag,
                    () -> service.requestRestore(new ProfileId(UUID.randomUUID()), new BackupSetId(UUID.randomUUID())));
            assertThat(outcome)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                            DatabaseDisasterBackupService.RestoreOutcome.CodeIssued.class))
                    .extracting(DatabaseDisasterBackupService.RestoreOutcome.CodeIssued::code)
                    .asString()
                    .describedAs("the code issued under " + tag)
                    .matches("[0-9]{4}");
        }
    }

    private static <T> T underLocale(String tag, Supplier<T> work) {
        Locale before = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag(tag));
        try {
            return work.get();
        } finally {
            Locale.setDefault(before);
        }
    }
}
