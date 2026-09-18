package com.uxplima.uxmskyblock.core.domain.permission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registry mapping stable {@link PermissionKey}s to dense, runtime-only {@link PermissionId}s.
 * Fails fast on namespace collision during registration.
 */
public final class PermissionRegistry {

    private final Map<PermissionKey, PermissionId> keyToId = new ConcurrentHashMap<>();
    private final List<PermissionKey> idToKey = new ArrayList<>();
    private final Object lock = new Object();

    public PermissionRegistry() {}

    /**
     * Registers a new permission key. Fails fast if the key is already registered.
     *
     * @param key the stable namespaced key
     * @return the compiled dense PermissionId
     * @throws IllegalStateException if key collision occurs
     */
    public PermissionId register(PermissionKey key) {
        Objects.requireNonNull(key, "key must not be null");
        synchronized (lock) {
            if (keyToId.containsKey(key)) {
                throw new IllegalStateException("Duplicate permission key registration detected: " + key);
            }
            int index = idToKey.size();
            PermissionId id = PermissionId.of(index);
            idToKey.add(key);
            keyToId.put(key, id);
            return id;
        }
    }

    public Optional<PermissionId> findId(PermissionKey key) {
        return Optional.ofNullable(keyToId.get(key));
    }

    public PermissionId getIdOrThrow(PermissionKey key) {
        PermissionId id = keyToId.get(key);
        if (id == null) {
            throw new IllegalArgumentException("Unregistered permission key: " + key);
        }
        return id;
    }

    public Optional<PermissionKey> findKey(PermissionId id) {
        synchronized (lock) {
            if (id.index() >= 0 && id.index() < idToKey.size()) {
                return Optional.of(idToKey.get(id.index()));
            }
            return Optional.empty();
        }
    }

    public PermissionSet compileSet(Set<PermissionKey> keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        List<PermissionId> ids = new ArrayList<>(keys.size());
        for (PermissionKey k : keys) {
            ids.add(getIdOrThrow(k));
        }
        return PermissionSet.of(ids);
    }

    public Set<PermissionKey> resolveSet(PermissionSet set) {
        Objects.requireNonNull(set, "set must not be null");
        Set<PermissionKey> keys = new HashSet<>();
        synchronized (lock) {
            for (int i = 0; i < idToKey.size(); i++) {
                if (set.has(PermissionId.of(i))) {
                    keys.add(idToKey.get(i));
                }
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    public int size() {
        synchronized (lock) {
            return idToKey.size();
        }
    }

    public Set<PermissionKey> allKeys() {
        synchronized (lock) {
            return Set.copyOf(idToKey);
        }
    }
}
