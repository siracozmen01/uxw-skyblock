package com.uxplima.uxmskyblock.bukkit.arrival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EnderPearl;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import com.uxplima.uxmskyblock.bukkit.test.InlineSchedulerPort;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * An arrival Folia never announced is put to the same rules as one Paper did.
 *
 * <p>Folia fires no teleport event for an asynchronous teleport, a {@code /tp}, an ender pearl or a
 * respawn. A quarantined island, a bankrupt one and a closed chunk all refused visitors in a teleport
 * event, so on Folia anyone could walk in by {@code /is visit}. These moves are made here the way Folia
 * makes them: the player is simply somewhere else, and no event says so.
 */
class AnArrivalNobodyAnnouncedIsStillJudgedTest extends MockBukkitHarness {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private final List<Arrival> judged = new ArrayList<>();
    private final List<Arrival> told = new ArrayList<>();
    private World world;
    private Location refuge;
    private PlayerMock ada;
    private ArrivalWatch watch;
    private boolean refuse;

    @BeforeEach
    void setUp() {
        world = server.addSimpleWorld("world");
        refuge = new Location(world, -7, 70, -7);
        ada = createMovingPlayer("Ada");
        ada.setLocation(new Location(world, 0, 64, 0));
        watch = new ArrivalWatch(new InlineSchedulerPort(), () -> refuge, Clock.fixed(NOW, ZoneOffset.UTC));
        watch.admit(arrival -> {
            judged.add(arrival);
            return refuse;
        });
        watch.observe(told::add);
        watch.sample(ada);
        judged.clear();
        told.clear();
    }

    @Test
    @DisplayName("A teleport no event announced is judged on the next look, and told once no rule refuses it")
    void anUnannouncedTeleportIsJudged() {
        ada.setLocation(new Location(world, 5120, 101, 5120));

        watch.sample(ada);

        assertThat(judged).singleElement().satisfies(arrival -> {
            assertThat(arrival.cause()).isEqualTo(ArrivalCause.TELEPORT);
            assertThat(arrival.before()).isFalse();
            assertThat(java.util.Objects.requireNonNull(arrival.from()).getBlockX())
                    .isZero();
            assertThat(arrival.to().getBlockX()).isEqualTo(5120);
        });
        assertThat(told).hasSize(1);
    }

    @Test
    @DisplayName("A refused arrival nobody announced sends the player back where they were, and tells nobody")
    void aRefusedArrivalIsUndone() {
        refuse = true;
        ada.setLocation(new Location(world, 5120, 101, 5120));

        watch.sample(ada);

        assertThat(ada.getLocation().getBlockX()).isZero();
        assertThat(ada.getLocation().getBlockZ()).isZero();
        assertThat(told).isEmpty();
        watch.sample(ada);
        assertThat(judged)
                .describedAs("being sent back is not an arrival of its own")
                .hasSize(1);
    }

    @Test
    @DisplayName("A walk explained by its move events is no arrival")
    void aWalkIsNoArrival() {
        Location from = ada.getLocation();
        Location to = new Location(world, 3, 64, 0);
        watch.onMove(new PlayerMoveEvent(ada, from, to));
        ada.setLocation(to);

        watch.sample(ada);

        assertThat(judged).isEmpty();
        assertThat(told).isEmpty();
    }

    @Test
    @DisplayName("A teleport Paper announced is judged by its event, and not a second time by the look")
    void anAnnouncedTeleportIsJudgedOnce() {
        Location to = new Location(world, 5120, 101, 5120);
        PlayerTeleportEvent event = new PlayerTeleportEvent(ada, ada.getLocation(), to);

        watch.onTeleport(event);
        watch.afterTeleport(event);
        ada.setLocation(to);
        watch.sample(ada);

        assertThat(judged)
                .singleElement()
                .satisfies(arrival -> assertThat(arrival.before()).isTrue());
        assertThat(told).hasSize(1);
    }

    @Test
    @DisplayName("A teleport Paper announced and a rule refused is cancelled, not undone")
    void anAnnouncedRefusalCancels() {
        refuse = true;
        PlayerTeleportEvent event =
                new PlayerTeleportEvent(ada, ada.getLocation(), new Location(world, 5120, 101, 5120));

        watch.onTeleport(event);

        assertThat(event.isCancelled()).isTrue();
        assertThat(ada.getLocation().getBlockX()).isZero();
    }

    @Test
    @DisplayName("A change of place after a death is a respawn, and a refused respawn goes to the refuge")
    void aRespawnIsKnownAndRefusedToTheRefuge() {
        refuse = true;
        watch.onDeath(mock(PlayerDeathEvent.class, call -> ada));
        ada.setLocation(new Location(world, 300, 80, 300));

        watch.sample(ada);

        assertThat(judged)
                .singleElement()
                .satisfies(arrival -> assertThat(arrival.cause()).isEqualTo(ArrivalCause.RESPAWN));
        assertThat(ada.getLocation().getBlockX())
                .describedAs("not back to where they died")
                .isEqualTo(-7);
    }

    @Test
    @DisplayName("A change of place just after the player's pearl landed is the pearl's doing")
    void aPearlIsKnown() {
        EnderPearl pearl = mock(EnderPearl.class);
        when(pearl.getShooter()).thenReturn(ada);
        ProjectileHitEvent hit = mock(ProjectileHitEvent.class);
        when(hit.getEntity()).thenReturn(pearl);
        watch.onPearl(hit);
        ada.setLocation(new Location(world, 12, 64, 0));

        watch.sample(ada);

        assertThat(judged)
                .singleElement()
                .satisfies(arrival -> assertThat(arrival.cause()).isEqualTo(ArrivalCause.PEARL));
    }

    @Test
    @DisplayName("A login is an arrival, and a refused login goes to the refuge")
    void aLoginIsAnArrival() {
        refuse = true;
        PlayerMock late = createMovingPlayer("Late");
        late.setLocation(new Location(world, 40, 64, 40));

        watch.onJoin(new PlayerJoinEvent(late, net.kyori.adventure.text.Component.empty()));

        assertThat(judged).singleElement().satisfies(arrival -> {
            assertThat(arrival.cause()).isEqualTo(ArrivalCause.JOIN);
            assertThat(arrival.from()).isNull();
        });
        assertThat(late.getLocation().getBlockX()).isEqualTo(-7);
    }

    @Test
    @DisplayName("A rule that throws is passed over and the next rule still decides")
    void aBrokenRuleDoesNotStopTheRest() {
        ArrivalWatch guarded =
                new ArrivalWatch(new InlineSchedulerPort(), () -> refuge, Clock.fixed(NOW, ZoneOffset.UTC));
        guarded.admit(arrival -> {
            throw new IllegalStateException("broken rule");
        });
        guarded.admit(arrival -> true);
        guarded.sample(ada);
        Location before = ada.getLocation();
        ada.setLocation(new Location(world, 5120, 101, 5120));

        guarded.sample(ada);

        assertThat(ada.getLocation().getBlockX()).isEqualTo(before.getBlockX());
    }
}
