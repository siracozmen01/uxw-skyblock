package com.uxplima.uxmskyblock.core.domain.message;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a stored notice carries, beside the message it names.
 *
 * <p>A notice, whether it goes to one player's inbox or to an island's activity feed, names a
 * message in the operator's catalogue and carries the values that message has holes for. It never carries the sentence itself: a sentence a player reads is written in a
 * language file, and a player who reads Turkish must not be told in English what happened to their
 * island while they were away, months after the operator changed the wording.
 *
 * <p>The packing is deliberately dull. Unit separators, which cannot occur in a player name or an
 * island name, so nothing here has to escape anything.
 */
public final class MessagePayload {

    private static final char PAIR = '\u001f';
    private static final char BETWEEN = '\u001e';

    private MessagePayload() {}

    /** Packs the values a message has holes for into one stored string. */
    public static String pack(Map<String, String> values) {
        Objects.requireNonNull(values, "values must not be null");
        StringBuilder packed = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            if (!packed.isEmpty()) {
                packed.append(BETWEEN);
            }
            packed.append(strip(entry.getKey())).append(PAIR).append(strip(entry.getValue()));
        }
        return packed.toString();
    }

    /**
     * Reads back what {@link #pack} wrote.
     *
     * <p>Anything that is not a packed pair comes back under the key {@code body}, because a row
     * written before this shape existed still has to be readable to the player it belongs to.
     */
    public static Map<String, String> unpack(String packed) {
        Objects.requireNonNull(packed, "packed must not be null");
        Map<String, String> values = new LinkedHashMap<>();
        if (packed.isEmpty()) {
            return values;
        }
        if (packed.indexOf(PAIR) < 0) {
            values.put("body", packed);
            return values;
        }
        for (String pair : packed.split(String.valueOf(BETWEEN), -1)) {
            int at = pair.indexOf(PAIR);
            if (at <= 0) {
                continue;
            }
            values.put(pair.substring(0, at), pair.substring(at + 1));
        }
        return values;
    }

    private static String strip(String raw) {
        return raw.replace(PAIR, ' ').replace(BETWEEN, ' ');
    }
}
