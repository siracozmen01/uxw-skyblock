package com.uxplima.uxmskyblock.bukkit.module.builtin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.uxplima.uxmskyblock.bukkit.config.BankConfiguration;
import com.uxplima.uxmskyblock.core.application.bank.IslandBankruptcyService;
import com.uxplima.uxmskyblock.core.application.module.ModuleContext;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.domain.bank.BankruptcyCycleResult;
import com.uxplima.uxmskyblock.core.domain.bank.IslandUpkeepPolicy;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.module.ModuleState;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BankUpkeepFeatureModuleTest {

    @Test
    @DisplayName("Module registers IslandBankruptcyService upon enable and schedules task if enabled")
    void registersServiceOnEnableWhenUpkeepActive() throws Exception {
        IslandBankruptcyService service = mock(IslandBankruptcyService.class);
        IslandUpkeepPolicy activePolicy =
                new IslandUpkeepPolicy(true, Duration.ofHours(24), 50_000L, 10_000L, Duration.ofDays(3), true);
        BankConfiguration config = new BankConfiguration(activePolicy);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        AutoCloseable task = mock(AutoCloseable.class);
        ServerNodeId nodeId = new ServerNodeId("test-node-1");

        when(scheduler.repeatAsync(any(), any(), any())).thenReturn(task);

        BankUpkeepFeatureModule module = new BankUpkeepFeatureModule(service, config, scheduler, List::of, nodeId);

        assertThat(module.descriptor().id()).isEqualTo("bank-upkeep");
        assertThat(module.descriptor().provides()).contains("island-bank-upkeep", "bankruptcy-protection");
        assertThat(module.bankruptcyService()).isSameAs(service);
        assertThat(module.configuration()).isSameAs(config);

        ModuleContext context = mock(ModuleContext.class);
        module.enable(context);

        assertThat(module.state()).isEqualTo(ModuleState.ENABLED);
        verify(context).registerService(IslandBankruptcyService.class, service);
        verify(context).registerService(BankConfiguration.class, config);
        verify(scheduler).repeatAsync(any(), any(), any());

        module.disable();
        assertThat(module.state()).isEqualTo(ModuleState.DISABLED);
        verify(task).close();
    }

    @Test
    @DisplayName("runUpkeepCycle iterates over active islands and delegates to service")
    void runUpkeepCycleIteratesIslands() {
        IslandBankruptcyService service = mock(IslandBankruptcyService.class);
        IslandUpkeepPolicy activePolicy =
                new IslandUpkeepPolicy(true, Duration.ofHours(24), 50_000L, 10_000L, Duration.ofDays(3), true);
        BankConfiguration config = new BankConfiguration(activePolicy);
        SchedulerPort scheduler = mock(SchedulerPort.class);
        ServerNodeId nodeId = new ServerNodeId("test-node-1");

        IslandId islandId = new IslandId(UUID.randomUUID());
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.parse("2026-09-19T12:00:00Z"));

        when(service.processUpkeepCycle(eq(islandId), anyInt(), any(), eq(nodeId)))
                .thenReturn(new BankruptcyCycleResult.Paid(50_000L, 100_000L));

        BankUpkeepFeatureModule module =
                new BankUpkeepFeatureModule(service, config, scheduler, () -> List.of(island), nodeId);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        module.runUpkeepCycle(now);

        verify(service, times(1)).processUpkeepCycle(eq(islandId), anyInt(), eq(now), eq(nodeId));
    }
}
