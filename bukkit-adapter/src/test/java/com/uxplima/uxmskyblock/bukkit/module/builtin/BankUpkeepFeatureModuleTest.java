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
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    @DisplayName("A period already settled on this node is skipped without reading an island")
    void aSettledPeriodReadsNothing() {
        IslandBankruptcyService service = mock(IslandBankruptcyService.class);
        IslandUpkeepPolicy policy =
                new IslandUpkeepPolicy(true, Duration.ofHours(24), 50_000L, 10_000L, Duration.ofDays(3), true);
        ServerNodeId nodeId = new ServerNodeId("test-node-1");
        Island island = island();
        AtomicInteger reads = new AtomicInteger();
        when(service.processUpkeepCycle(eq(island.id()), anyInt(), any(), eq(nodeId)))
                .thenReturn(new BankruptcyCycleResult.Paid(60_000L, 40_000L));
        BankUpkeepFeatureModule module = new BankUpkeepFeatureModule(
                service,
                new BankConfiguration(policy),
                mock(SchedulerPort.class),
                () -> {
                    reads.incrementAndGet();
                    return List.of(island);
                },
                nodeId);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        module.runUpkeepCycle(now);
        module.runUpkeepCycle(now.plusSeconds(60));
        module.runUpkeepCycle(now.plus(Duration.ofHours(11)));

        assertThat(reads).describedAs("island reads inside one period").hasValue(1);
        verify(service, times(1)).processUpkeepCycle(eq(island.id()), anyInt(), any(), eq(nodeId));

        module.runUpkeepCycle(now.plus(Duration.ofHours(24)));
        assertThat(reads).describedAs("the next period reads them again").hasValue(2);
    }

    @Test
    @DisplayName("An island whose charge was deferred is tried again on the next poll, alone")
    void aDeferredIslandIsRetriedAlone() {
        IslandBankruptcyService service = mock(IslandBankruptcyService.class);
        IslandUpkeepPolicy policy =
                new IslandUpkeepPolicy(true, Duration.ofHours(24), 50_000L, 10_000L, Duration.ofDays(3), true);
        ServerNodeId nodeId = new ServerNodeId("test-node-1");
        Island paying = island();
        Island moving = island();
        AtomicInteger reads = new AtomicInteger();
        when(service.processUpkeepCycle(eq(paying.id()), anyInt(), any(), eq(nodeId)))
                .thenReturn(new BankruptcyCycleResult.Paid(60_000L, 40_000L));
        when(service.processUpkeepCycle(eq(moving.id()), anyInt(), any(), eq(nodeId)))
                .thenReturn(new BankruptcyCycleResult.Deferred("StaleVersion"))
                .thenReturn(new BankruptcyCycleResult.Paid(60_000L, 40_000L));
        BankUpkeepFeatureModule module = new BankUpkeepFeatureModule(
                service,
                new BankConfiguration(policy),
                mock(SchedulerPort.class),
                () -> {
                    reads.incrementAndGet();
                    return List.of(paying, moving);
                },
                nodeId);

        Instant now = Instant.parse("2026-09-19T12:00:00Z");
        module.runUpkeepCycle(now);
        module.runUpkeepCycle(now.plusSeconds(60));
        module.runUpkeepCycle(now.plusSeconds(120));

        verify(service, times(1)).processUpkeepCycle(eq(paying.id()), anyInt(), any(), eq(nodeId));
        verify(service, times(2)).processUpkeepCycle(eq(moving.id()), anyInt(), any(), eq(nodeId));
        assertThat(reads).describedAs("islands read for the retry").hasValue(1);
    }

    private static Island island() {
        return Island.create(
                new IslandId(UUID.randomUUID()),
                IslandBounds.fromCenterAndRadius(0, 0, 50),
                new PlayerUuid(UUID.randomUUID()),
                new ProfileId(UUID.randomUUID()),
                Instant.parse("2026-09-19T12:00:00Z"));
    }
}
