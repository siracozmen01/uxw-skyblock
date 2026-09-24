package com.uxplima.uxmskyblock.bukkit.i18n;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * A span of time as the reader writes it.
 *
 * <p>Six places wrote durations themselves, each with the English letters d, h, m and s and each a
 * little differently: a Turkish player read "2m 5s" on a cooldown and "None" on a booster. The units
 * and the word for no time at all are the catalogue's {@code time} group, so every language, and
 * every operator, writes them their own way.
 */
public final class DurationText {

    private DurationText() {}

    /**
     * {@code duration} in {@code viewer}'s language: days, hours, minutes and seconds, each only when
     * it is not zero, and the catalogue's word for none when nothing is left. A part of a second left
     * over counts as a whole one, so a wait that has not ended never reads as none.
     */
    public static String of(Messages messages, Audience viewer, Duration duration) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative() || duration.isZero()) {
            return plain(messages, viewer, "time.none", 0);
        }
        long total = (duration.toMillis() + 999) / 1000;
        long[] amounts = {total / 86_400, total % 86_400 / 3600, total % 3600 / 60, total % 60};
        String[] keys = {"time.days", "time.hours", "time.minutes", "time.seconds"};
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            if (amounts[i] > 0) {
                parts.add(plain(messages, viewer, keys[i], amounts[i]));
            }
        }
        return String.join(" ", parts);
    }

    /**
     * {@code duration} in its coarsest unit that is still true, for a line that only needs to say
     * roughly how long: "3 days ago" rather than every part of it. Under a minute it is seconds, and
     * nothing at all reads as no seconds.
     */
    public static String coarse(Messages messages, Audience viewer, Duration duration) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(duration, "duration");
        long total = Math.max(0L, duration.toSeconds());
        if (total >= 86_400) {
            return plain(messages, viewer, "time.days", total / 86_400);
        }
        if (total >= 3600) {
            return plain(messages, viewer, "time.hours", total / 3600);
        }
        if (total >= 60) {
            return plain(messages, viewer, "time.minutes", total / 60);
        }
        return plain(messages, viewer, "time.seconds", total);
    }

    private static String plain(Messages messages, Audience viewer, String key, long amount) {
        return PlainTextComponentSerializer.plainText()
                .serialize(messages.renderPlain(viewer, key, Placeholder.unparsed("n", Long.toString(amount))));
    }
}
