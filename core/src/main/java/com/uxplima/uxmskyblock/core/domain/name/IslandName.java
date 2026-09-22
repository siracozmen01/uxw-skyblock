package com.uxplima.uxmskyblock.core.domain.name;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value object representing a validated custom island name (Section 2.41).
 * Constraints:
 * <ul>
 *   <li>Length: 3 to 16 characters</li>
 *   <li>Characters: Alphanumeric, underscores, hyphens, and single spaces</li>
 * </ul>
 */
public record IslandName(String value) {

    /** The shortest a name may be. */
    public static final int MIN_LENGTH = 3;

    /** The longest a name may be. */
    public static final int MAX_LENGTH = 16;

    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9_ -]{1,14}[a-zA-Z0-9]$");

    public IslandName {
        Objects.requireNonNull(value, "Island name value must not be null");
        String trimmed = value.trim();
        if (trimmed.length() < MIN_LENGTH || trimmed.length() > MAX_LENGTH) {
            throw new IslandNameRefusedException(
                    IslandNameRefusedException.Reason.LENGTH,
                    "Island name length must be between 3 and 16 characters: " + trimmed.length());
        }
        if (!VALID_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new IslandNameRefusedException(
                    IslandNameRefusedException.Reason.CHARACTERS,
                    "Island name contains invalid characters. Must be alphanumeric with optional spaces, underscores, or hyphens: "
                            + trimmed);
        }
        value = trimmed;
    }

    public static IslandName of(String value) {
        return new IslandName(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
