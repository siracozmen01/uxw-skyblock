package com.uxplima.uxmskyblock.api;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Global provider registering and retrieving the singleton {@link UxmSkyblockApi} instance.
 */
public final class UxmSkyblockApiProvider {

    private static final AtomicReference<UxmSkyblockApi> INSTANCE = new AtomicReference<>();

    private UxmSkyblockApiProvider() {}

    public static UxmSkyblockApi get() {
        UxmSkyblockApi api = INSTANCE.get();
        if (api == null) {
            throw new IllegalStateException("UXPLIMA Skyblock API has not been initialized yet.");
        }
        return api;
    }

    public static void register(UxmSkyblockApi api) {
        Objects.requireNonNull(api, "api must not be null");
        INSTANCE.set(api);
    }

    public static void unregister() {
        INSTANCE.set(null);
    }

    public static boolean isRegistered() {
        return INSTANCE.get() != null;
    }
}
