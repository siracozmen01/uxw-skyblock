package com.uxplima.uxmskyblock.bukkit.i18n;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;

import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A span of time reads in the reader's language, with the units the catalogue gives.
 *
 * <p>Six places wrote durations with the English letters d, h, m and s, and one wrote "None": a
 * Turkish player read "2m 5s" on a cooldown and "None" on a booster.
 */
class ATimeIsWrittenInTheReadersLanguageTest extends MockBukkitHarness {

    private final Messages messages = Messages.bundled();

    @Test
    @DisplayName("Days, hours, minutes and seconds read in English and in Turkish, the zero parts left out")
    void theUnitsAreTheReaders() {
        Duration span = Duration.ofDays(1).plusHours(2).plusSeconds(5);
        PlayerMock english = createPlayer("Reader");
        PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));

        assertThat(DurationText.of(messages, english, span)).isEqualTo("1d 2h 5s");
        assertThat(DurationText.of(messages, turkish, span)).isEqualTo("1g 2sa 5sn");
    }

    @Test
    @DisplayName("No time left is the catalogue's word for none, and a part of a second is a whole one")
    void noneAndPartsOfASecond() {
        PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));

        assertThat(DurationText.of(messages, turkish, Duration.ZERO)).isEqualTo("yok");
        assertThat(DurationText.of(messages, createPlayer("Reader"), Duration.ZERO))
                .isEqualTo("none");
        assertThat(DurationText.of(messages, turkish, Duration.ofMillis(400))).isEqualTo("1sn");
    }

    @Test
    @DisplayName("A rough span reads in its coarsest true unit, in the reader's language")
    void aRoughSpanIsTheCoarsestUnit() {
        PlayerMock turkish = createPlayer("Okur");
        turkish.setLocale(Locale.forLanguageTag("tr"));

        assertThat(DurationText.coarse(messages, turkish, Duration.ofDays(3).plusHours(5)))
                .isEqualTo("3g");
        assertThat(DurationText.coarse(messages, turkish, Duration.ofSeconds(90)))
                .isEqualTo("1dk");
        assertThat(DurationText.coarse(messages, turkish, Duration.ofSeconds(20)))
                .isEqualTo("20sn");
        assertThat(DurationText.coarse(messages, turkish, Duration.ofSeconds(-5)))
                .isEqualTo("0sn");
        assertThat(DurationText.coarse(messages, createPlayer("Reader"), Duration.ofHours(2)))
                .isEqualTo("2h");
    }
}
