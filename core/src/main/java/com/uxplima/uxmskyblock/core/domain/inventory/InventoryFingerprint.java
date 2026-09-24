package com.uxplima.uxmskyblock.core.domain.inventory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.jspecify.annotations.Nullable;

/**
 * What a serialised inventory holds, reduced to one comparable string.
 *
 * <p>A journaled mutation records the inventory's fingerprint before it and the one it expects after
 * it. The same function has to produce both and read them back, or recovery after a crash compares
 * strings that could never match: the inventory it finds is fingerprinted here, and so is every
 * inventory a mutation records.
 */
public final class InventoryFingerprint {

    /** The fingerprint of an inventory with nothing serialised in it. */
    public static final String EMPTY = "0".repeat(64);

    private InventoryFingerprint() {}

    /** The SHA-256 of {@code serialised} in lower-case hex, or {@link #EMPTY} for no bytes at all. */
    public static String of(byte @Nullable [] serialised) {
        if (serialised == null || serialised.length == 0) {
            return EMPTY;
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialised));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime ships SHA-256", e);
        }
    }
}
