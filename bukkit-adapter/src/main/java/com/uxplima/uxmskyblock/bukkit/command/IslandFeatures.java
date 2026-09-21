package com.uxplima.uxmskyblock.bukkit.command;

import com.uxplima.uxmskyblock.bukkit.dimension.IslandDimensionListener;
import com.uxplima.uxmskyblock.bukkit.menu.IslandBoosterMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandControlMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandMissionsMenu;
import com.uxplima.uxmskyblock.bukkit.menu.IslandResetConfirmationMenu;
import com.uxplima.uxmskyblock.core.application.antiabuse.IslandAntiAbuseService;
import com.uxplima.uxmskyblock.core.application.booster.IslandBoosterService;
import com.uxplima.uxmskyblock.core.application.boundary.IslandBoundaryService;
import com.uxplima.uxmskyblock.core.application.chat.IslandChatService;
import com.uxplima.uxmskyblock.core.application.freeze.IslandAdminFreezeService;
import com.uxplima.uxmskyblock.core.application.inactivity.IslandInactivityService;
import com.uxplima.uxmskyblock.core.application.limit.IslandLimitService;
import com.uxplima.uxmskyblock.core.application.recycle.IslandRecycleService;
import com.uxplima.uxmskyblock.core.application.upgrade.IslandUpgradeService;
import com.uxplima.uxmskyblock.core.application.worth.IslandWorthService;
import org.jspecify.annotations.Nullable;

/**
 * The parts of the island command that an operator may switch off.
 *
 * <p>Each one is absent when its module is disabled, and the command tree leaves out the branch
 * that needs it. They were fourteen nullable parameters in a row on a constructor, and the caller
 * that wanted none of them passed fourteen nulls in the right order. Nothing would have caught the
 * wrong order between two of the same type.
 *
 * <p>Built through {@link #builder()}, where each one is named.
 */
public record IslandFeatures(
        @Nullable IslandControlMenu controlMenu,
        @Nullable IslandChatService chatService,
        @Nullable IslandInactivityService inactivityService,
        @Nullable IslandAdminFreezeService freezeService,
        @Nullable IslandMissionsMenu missionsMenu,
        @Nullable IslandBoundaryService boundaryService,
        @Nullable IslandRecycleService recycleService,
        @Nullable IslandResetConfirmationMenu resetMenu,
        @Nullable IslandWorthService worthService,
        @Nullable IslandDimensionListener dimensionListener,
        @Nullable IslandLimitService limitService,
        @Nullable IslandAntiAbuseService antiAbuseService,
        @Nullable IslandBoosterService boosterService,
        @Nullable IslandBoosterMenu boosterMenu,
        @Nullable IslandUpgradeService upgradeService) {

    /** Every optional module switched off, which is what a bare island command is. */
    public static IslandFeatures none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Names each module as it is added, so an absent one is an omission rather than a null. */
    public static final class Builder {

        private @Nullable IslandControlMenu controlMenu;
        private @Nullable IslandChatService chatService;
        private @Nullable IslandInactivityService inactivityService;
        private @Nullable IslandAdminFreezeService freezeService;
        private @Nullable IslandMissionsMenu missionsMenu;
        private @Nullable IslandBoundaryService boundaryService;
        private @Nullable IslandRecycleService recycleService;
        private @Nullable IslandResetConfirmationMenu resetMenu;
        private @Nullable IslandWorthService worthService;
        private @Nullable IslandDimensionListener dimensionListener;
        private @Nullable IslandLimitService limitService;
        private @Nullable IslandAntiAbuseService antiAbuseService;
        private @Nullable IslandBoosterService boosterService;
        private @Nullable IslandBoosterMenu boosterMenu;
        private @Nullable IslandUpgradeService upgradeService;

        private Builder() {}

        public Builder upgradeService(@Nullable IslandUpgradeService value) {
            this.upgradeService = value;
            return this;
        }

        public Builder controlMenu(@Nullable IslandControlMenu value) {
            this.controlMenu = value;
            return this;
        }

        public Builder chatService(@Nullable IslandChatService value) {
            this.chatService = value;
            return this;
        }

        public Builder inactivityService(@Nullable IslandInactivityService value) {
            this.inactivityService = value;
            return this;
        }

        public Builder freezeService(@Nullable IslandAdminFreezeService value) {
            this.freezeService = value;
            return this;
        }

        public Builder missionsMenu(@Nullable IslandMissionsMenu value) {
            this.missionsMenu = value;
            return this;
        }

        public Builder boundaryService(@Nullable IslandBoundaryService value) {
            this.boundaryService = value;
            return this;
        }

        public Builder recycleService(@Nullable IslandRecycleService value) {
            this.recycleService = value;
            return this;
        }

        public Builder resetMenu(@Nullable IslandResetConfirmationMenu value) {
            this.resetMenu = value;
            return this;
        }

        public Builder worthService(@Nullable IslandWorthService value) {
            this.worthService = value;
            return this;
        }

        public Builder dimensionListener(@Nullable IslandDimensionListener value) {
            this.dimensionListener = value;
            return this;
        }

        public Builder limitService(@Nullable IslandLimitService value) {
            this.limitService = value;
            return this;
        }

        public Builder antiAbuseService(@Nullable IslandAntiAbuseService value) {
            this.antiAbuseService = value;
            return this;
        }

        public Builder boosterService(@Nullable IslandBoosterService value) {
            this.boosterService = value;
            return this;
        }

        public Builder boosterMenu(@Nullable IslandBoosterMenu value) {
            this.boosterMenu = value;
            return this;
        }

        public IslandFeatures build() {
            return new IslandFeatures(
                    controlMenu,
                    chatService,
                    inactivityService,
                    freezeService,
                    missionsMenu,
                    boundaryService,
                    recycleService,
                    resetMenu,
                    worthService,
                    dimensionListener,
                    limitService,
                    antiAbuseService,
                    boosterService,
                    boosterMenu,
                    upgradeService);
        }
    }
}
