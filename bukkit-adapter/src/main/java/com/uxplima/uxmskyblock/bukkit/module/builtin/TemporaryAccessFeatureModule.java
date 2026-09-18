package com.uxplima.uxmskyblock.bukkit.module.builtin;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.uxplima.uxmskyblock.bukkit.config.TemporaryAccessConfiguration;
import com.uxplima.uxmskyblock.core.application.access.TemporaryAccessService;
import com.uxplima.uxmskyblock.core.application.module.AbstractFeatureModule;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.module.ModuleDescriptor;
import org.jspecify.annotations.Nullable;

/**
 * Built-in feature module managing temporary access grants, distributed termination
 * anchors, and periodic database expiration scrubbing.
 */
public final class TemporaryAccessFeatureModule extends AbstractFeatureModule {

    private final TemporaryAccessService accessService;
    private final SchedulerPort scheduler;
    private final TemporaryAccessConfiguration configuration;
    private @Nullable AutoCloseable purgeTask;

    public TemporaryAccessFeatureModule(
            TemporaryAccessService accessService, SchedulerPort scheduler, TemporaryAccessConfiguration configuration) {
        super(new ModuleDescriptor(
                "temporary-access",
                "1.0.0",
                List.of("core >= 1.0.0"),
                List.of(),
                List.of("temporary-access-grants"),
                ">=1.0.0",
                false));
        this.accessService = Objects.requireNonNull(accessService, "accessService must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
    }

    @Override
    protected void onEnable(ModuleContext context) {
        context.registerService(TemporaryAccessService.class, accessService);

        this.purgeTask = scheduler.repeatAsync(
                () -> accessService.purgeExpired(Instant.now()), Duration.ofSeconds(10), configuration.purgeInterval());
    }

    @Override
    protected void onDisable() {
        if (purgeTask != null) {
            try {
                purgeTask.close();
            } catch (Exception expected) {
                // Best-effort cancellation
            }
            purgeTask = null;
        }
    }
}
