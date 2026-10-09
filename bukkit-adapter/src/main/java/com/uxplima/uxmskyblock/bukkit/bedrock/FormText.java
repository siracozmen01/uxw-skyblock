package com.uxplima.uxmskyblock.bukkit.bedrock;

import java.util.Objects;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/**
 * A line drawn for a Floodgate form.
 *
 * <p>A form takes a string, and a Bedrock client reads the sixteen legacy colours and nothing finer. The
 * palette is drawn in exact colours, so a coloured line reached a form as a run of hex codes and the
 * client wrote the codes out. A form is drawn in the client's own style instead, the way the library's
 * forms are, and the words carry the meaning the colours carried in a chest.
 */
public final class FormText {

    private FormText() {}

    /** {@code line} as the words a form shows. */
    public static String of(Component line) {
        return PlainTextComponentSerializer.plainText()
                .serialize(Objects.requireNonNull(line, "line must not be null"));
    }
}
