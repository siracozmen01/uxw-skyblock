package com.uxplima.uxmskyblock.core.application.lifecycle;

import java.util.Set;

import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;

/** What lifecycle events still owe players, kept until each player's next session pays it. */
public interface LifecycleOwedEffectsPort {

    /** Adds effects to what the player owes. An effect already owed stays owed once. */
    void owe(PlayerUuid playerUuid, Set<LifecycleEffect> effects);

    /** Everything the player owes, empty when nothing. */
    Set<LifecycleEffect> owed(PlayerUuid playerUuid);

    /** Records that these effects were paid. Anything else the player owes stays owed. */
    void settle(PlayerUuid playerUuid, Set<LifecycleEffect> paid);
}
