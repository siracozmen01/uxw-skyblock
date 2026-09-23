package com.uxplima.uxmskyblock.core.domain.warp;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Validated, normalized warp name (1..32 characters, alphanumeric, underscores, and dashes).
 */
public record WarpName(String value) {

    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,32}$");

    public WarpName {
        Objects.requireNonNull(value, "value must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || !VALID_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidWarpNameException(value);
        }
        value = trimmed.toLowerCase(Locale.ROOT);
    }

    public static WarpName of(String name) {
        return new WarpName(name);
    }

    @Override
    public String toString() {
        return value;
    }
}
