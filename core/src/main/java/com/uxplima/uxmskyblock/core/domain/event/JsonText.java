package com.uxplima.uxmskyblock.core.domain.event;

/**
 * Text made safe to sit between the quotes of a JSON string.
 *
 * <p>The outbox payloads are written by hand, with the values dropped between quotes as they stood. A
 * freeze reason is whatever an administrator typed, so a reason with a quote or a line break in it
 * wrote a payload no reader could parse, and every node that consumed the event failed on it.
 */
public final class JsonText {

    private JsonText() {}

    /** The text with every character JSON gives meaning to written as its escape. */
    public static String escaped(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < ' ') {
                        String hex = "000" + Integer.toHexString(c);
                        out.append("\\u").append(hex.substring(hex.length() - 4));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** The text as a whole JSON string, quotes included. */
    public static String quoted(String text) {
        return '"' + escaped(text) + '"';
    }
}
