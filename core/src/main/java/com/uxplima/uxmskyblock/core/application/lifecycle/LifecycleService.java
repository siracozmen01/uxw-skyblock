package com.uxplima.uxmskyblock.core.application.lifecycle;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.uxplima.uxmskyblock.core.domain.gamemode.GameModeType;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEffect;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecycleEvent;
import com.uxplima.uxmskyblock.core.domain.lifecycle.LifecyclePolicy;
import com.uxplima.uxmskyblock.core.domain.profile.ProfileType;

/**
 * What a lifecycle event does to a player, from the operator's rules, the mode the player's island
 * plays and the ruleset of their profile.
 *
 * <p>A leave, a kick and a reset are owed before anything is done, so a player who is not on this
 * server pays them when they next play on any. A death is answered at once, on the player's own
 * thread, from what is already known.
 */
public final class LifecycleService {

    private final LifecyclePolicy policy;
    private final LifecycleOwedEffectsPort owed;
    private final Function<ProfileId, GameModeType> modes;
    private final Function<ProfileId, Optional<GameModeType>> knownModes;
    private final Function<ProfileId, ProfileType> rulesets;
    private final Function<ProfileId, Optional<ProfileType>> knownRulesets;

    /**
     * @param modes the mode a profile's island plays, which may read storage
     * @param knownModes the mode if it is already known, which never does
     * @param rulesets a profile's ruleset, which may read storage
     * @param knownRulesets the ruleset if it is already known, which never does
     */
    public LifecycleService(
            LifecyclePolicy policy,
            LifecycleOwedEffectsPort owed,
            Function<ProfileId, GameModeType> modes,
            Function<ProfileId, Optional<GameModeType>> knownModes,
            Function<ProfileId, ProfileType> rulesets,
            Function<ProfileId, Optional<ProfileType>> knownRulesets) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.owed = Objects.requireNonNull(owed, "owed must not be null");
        this.modes = Objects.requireNonNull(modes, "modes must not be null");
        this.knownModes = Objects.requireNonNull(knownModes, "knownModes must not be null");
        this.rulesets = Objects.requireNonNull(rulesets, "rulesets must not be null");
        this.knownRulesets = Objects.requireNonNull(knownRulesets, "knownRulesets must not be null");
    }

    /**
     * Records what a leave, a kick or a reset owes the player, and answers it. Off the player's thread.
     */
    public Set<LifecycleEffect> owe(LifecycleEvent event, PlayerUuid player, ProfileId profile) {
        Objects.requireNonNull(event, "event must not be null");
        if (event == LifecycleEvent.DEATH) {
            throw new IllegalArgumentException("A death is answered at once, never owed");
        }
        Set<LifecycleEffect> effects = policy.effects(event, modes.apply(profile), rulesets.apply(profile));
        owed.owe(player, effects);
        return effects;
    }

    /**
     * What a death does, from what is already known about the profile. A mode not yet read is skyblock
     * and a ruleset not yet read is classic; the player's session reads both when it is made.
     */
    public Set<LifecycleEffect> onDeath(ProfileId profile) {
        GameModeType mode = knownModes.apply(profile).orElse(GameModeType.SKYBLOCK);
        ProfileType ruleset = knownRulesets.apply(profile).orElse(ProfileType.CLASSIC);
        return policy.effects(LifecycleEvent.DEATH, mode, ruleset);
    }

    /** Reads the profile's mode and ruleset ahead of a death. Off the player's thread. */
    public void learn(ProfileId profile) {
        var unusedMode = modes.apply(profile);
        var unusedRuleset = rulesets.apply(profile);
    }

    /** Everything the player still owes. Off the player's thread. */
    public Set<LifecycleEffect> owed(PlayerUuid player) {
        return owed.owed(player);
    }

    /** Records that these effects were paid. Off the player's thread. */
    public void settle(PlayerUuid player, Set<LifecycleEffect> paid) {
        owed.settle(player, paid.isEmpty() ? paid : EnumSet.copyOf(paid));
    }
}
