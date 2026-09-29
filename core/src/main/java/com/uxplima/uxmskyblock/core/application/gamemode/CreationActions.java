package com.uxplima.uxmskyblock.core.application.gamemode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The creation actions a server knows, each answered by exactly one provider.
 *
 * <p>Two providers for one name would leave it to chance which one builds a player's island, so the
 * second registration fails while the server starts rather than on some player's first island. A start
 * that names an action nobody provides fails before any of its actions has run, so no island is left
 * half built.
 *
 * @param <C> where the actions happen
 */
public final class CreationActions<C> {

    private final Map<String, CreationActionProvider<C>> providers = new ConcurrentHashMap<>();

    /**
     * Adds a provider.
     *
     * @throws IllegalStateException when another provider already answers its name
     */
    public void register(CreationActionProvider<C> provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        String name = key(provider.actionId());
        CreationActionProvider<C> earlier = providers.putIfAbsent(name, provider);
        if (earlier != null) {
            throw new IllegalStateException("The creation action '" + name + "' is provided twice, by "
                    + earlier.getClass().getName() + " and by "
                    + provider.getClass().getName());
        }
    }

    /** Whether every action the list names has a provider. */
    public boolean knowsAll(List<String> actionIds) {
        Objects.requireNonNull(actionIds, "actionIds must not be null");
        for (String actionId : actionIds) {
            if (!providers.containsKey(key(actionId))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Runs the actions in the order the list names them.
     *
     * @throws IllegalArgumentException when an action has no provider, before any action has run
     */
    public void run(List<String> actionIds, C context) {
        Objects.requireNonNull(actionIds, "actionIds must not be null");
        Objects.requireNonNull(context, "context must not be null");
        List<CreationActionProvider<C>> resolved = new ArrayList<>(actionIds.size());
        for (String actionId : actionIds) {
            CreationActionProvider<C> provider = providers.get(key(actionId));
            if (provider == null) {
                throw new IllegalArgumentException("No provider answers the creation action '" + actionId + "'");
            }
            resolved.add(provider);
        }
        for (CreationActionProvider<C> provider : resolved) {
            provider.apply(context);
        }
    }

    private static String key(String actionId) {
        Objects.requireNonNull(actionId, "actionId must not be null");
        String key = actionId.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) {
            throw new IllegalArgumentException("A creation action needs a name");
        }
        return key;
    }
}
