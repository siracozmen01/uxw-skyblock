package com.uxplima.uxmskyblock.core.domain.gamemode;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Something that is not an island and still has one node writing to it at a time: a game mode instance,
 * or a root a mode owns and names itself.
 *
 * <p>An island's writer is held in {@code island_authorities}. A Boxed claim, a TradeWinds vessel or a
 * Parkour course is played through its game mode instance, and its writer is held against the instance.
 * A mode that needs a writer for a root of its own names a provider and a key, and core keeps the lease
 * for it: no mode brings a table of its own for this.
 *
 * @param scope which kind of root this is
 * @param providerId who names the root: {@link #INSTANCE_PROVIDER} for an instance, a mode's own id otherwise
 * @param key the root, within its provider
 */
public record AuthorityRoot(Scope scope, String providerId, String key) {

    /** The provider every game mode instance is held under. */
    public static final String INSTANCE_PROVIDER = "uxm:instance";

    private static final Pattern NAMESPACED = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    private static final int LONGEST_PROVIDER = 64;
    private static final int LONGEST_KEY = 128;

    /** Which table a root's lease is kept in. */
    public enum Scope {
        /** {@code game_mode_instance_authorities}, keyed by the instance. */
        GAME_MODE_INSTANCE,
        /** {@code mode_owned_authorities}, keyed by the provider and the root. */
        MODE_OWNED
    }

    public AuthorityRoot {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(providerId, "providerId must not be null");
        Objects.requireNonNull(key, "key must not be null");
        if (!NAMESPACED.matcher(providerId).matches() || providerId.length() > LONGEST_PROVIDER) {
            throw new IllegalArgumentException("a provider is a lowercase namespace and key: " + providerId);
        }
        if (key.isBlank() || key.length() > LONGEST_KEY) {
            throw new IllegalArgumentException("a root key is between 1 and 128 characters");
        }
        if ((scope == Scope.GAME_MODE_INSTANCE) != INSTANCE_PROVIDER.equals(providerId)) {
            throw new IllegalArgumentException("only an instance is held under " + INSTANCE_PROVIDER);
        }
    }

    /** The root a game mode instance's writer is held against. */
    public static AuthorityRoot instance(GameModeInstanceId instanceId) {
        Objects.requireNonNull(instanceId, "instanceId must not be null");
        return new AuthorityRoot(
                Scope.GAME_MODE_INSTANCE, INSTANCE_PROVIDER, instanceId.value().toString());
    }

    /** A root a mode owns and names itself. */
    public static AuthorityRoot modeOwned(String providerId, String rootKey) {
        return new AuthorityRoot(Scope.MODE_OWNED, providerId, rootKey);
    }
}
