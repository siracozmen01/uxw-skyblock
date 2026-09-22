package com.uxplima.uxmskyblock.core.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A notification carries values, never a sentence.
 *
 * <p>A player who reads Turkish must not be told in English what happened to their island while
 * they were away, and an operator who rewrites a line must not find the old wording still sitting
 * in a table weeks later.
 */
class NotificationPayloadTest {

    @Test
    @DisplayName("What was packed comes back, in the order it was written")
    void whatWasPackedComesBack() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("player", "Owner");
        values.put("role", "MODERATOR");

        assertThat(NotificationPayload.unpack(NotificationPayload.pack(values))).containsExactlyEntriesOf(values);
    }

    @Test
    @DisplayName("A name with a space or a colon in it survives the trip")
    void anAwkwardNameSurvives() {
        Map<String, String> values = Map.of("player", "Some Name: with punctuation");

        assertThat(NotificationPayload.unpack(NotificationPayload.pack(values))).isEqualTo(values);
    }

    @Test
    @DisplayName("Nothing packed is nothing unpacked")
    void nothingIsNothing() {
        assertThat(NotificationPayload.pack(Map.of())).isEmpty();
        assertThat(NotificationPayload.unpack("")).isEmpty();
    }

    @Test
    @DisplayName("A row written before this shape existed still reads back as a body")
    void anOlderRowStillReads() {
        assertThat(NotificationPayload.unpack("{\"inviter\":\"Leader\"}"))
                .describedAs("a notice already in the table must still reach the player it belongs to")
                .containsEntry("body", "{\"inviter\":\"Leader\"}");
    }

    @Test
    @DisplayName("A separator inside a value cannot forge a second pair")
    void aSeparatorCannotForgeAPair() {
        Map<String, String> values = Map.of("player", "Owner\u001frole\u001eOWNER");

        Map<String, String> read = NotificationPayload.unpack(NotificationPayload.pack(values));

        assertThat(read).describedAs("one pair went in, one comes out").hasSize(1);
        assertThat(read.get("player")).doesNotContain("\u001f").doesNotContain("\u001e");
    }
}
