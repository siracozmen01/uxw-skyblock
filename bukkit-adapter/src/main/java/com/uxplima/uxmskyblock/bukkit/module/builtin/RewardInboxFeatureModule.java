package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.RewardInboxConfiguration;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.reward.RewardInboxService;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module managing the offline reward inbox, multi-protocol delivery orchestration,
 * and periodic expiration scrubbing.
 */
public final class RewardInboxFeatureModule extends AbstractFeatureModule {

    private final RewardInboxService rewardInboxService;
    private final SchedulerPort scheduler;
    private final RewardInboxConfiguration configuration;
    private @Nullable AutoCloseable expiryTask;

    public RewardInboxFeatureModule(
            RewardInboxService rewardInboxService, SchedulerPort scheduler, RewardInboxConfiguration configuration) {
        super(new ModuleDescriptor(
                "rewards",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("reward-inbox", "multi-protocol-delivery"),
                ">=1.0.0",
                false));
        this.rewardInboxService = Objects.requireNonNull(rewardInboxService, "rewardInboxService must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(RewardInboxService.class, rewardInboxService);

        this.expiryTask = scheduler.repeatAsync(
                () -> rewardInboxService.expirePendingRewards(Instant.now()),
                Duration.ofSeconds(15),
                configuration.expiryCheckInterval());
    }

    @Override
    protected void onDisable() {
        if (expiryTask != null) {
            try {
                expiryTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            expiryTask = null;
        }
    }
}
