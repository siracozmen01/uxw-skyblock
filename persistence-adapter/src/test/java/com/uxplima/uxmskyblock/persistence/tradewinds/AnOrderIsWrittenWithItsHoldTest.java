package com.uxplima.uxmskyblock.persistence.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.uxplima.uxmlib.storage.migration.MigrationRunner;
import com.uxplima.uxmlib.storage.sql.Database;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.HoldWrite;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.Kind;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.Order;
import com.uxplima.uxmskyblock.core.application.tradewinds.MarketOrdersPort.State;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VoyagesPort;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.persistence.island.RootAuthorityAdapter;
import com.uxplima.uxmskyblock.persistence.migration.SkyblockMigrations;
import com.uxplima.uxmskyblock.persistence.testfixture.DatabaseTestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An order is written with the hold it moves, under the vessel's lease and over the hold's version, and
 * its state moves only from the state it is in.
 */
class AnOrderIsWrittenWithItsHoldTest {

    private static final ServerNodeId NODE = ServerNodeId.of("node-a");
    private static final ServerNodeId OTHER = ServerNodeId.of("node-b");

    @TempDir
    Path dir;

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final PlayerUuid ada = PlayerUuid.of(UUID.randomUUID());

    @SuppressWarnings("NullAway.Init")
    private Database database;

    @SuppressWarnings("NullAway.Init")
    private SqlVesselsAdapter vessels;

    @SuppressWarnings("NullAway.Init")
    private SqlMarketOrdersAdapter orders;

    @SuppressWarnings("NullAway.Init")
    private SqlVoyagesAdapter voyages;

    private long epoch;

    @BeforeEach
    void setUp() throws Exception {
        database = DatabaseTestFixture.createSqliteFile(dir.resolve("orders.db"));
        new MigrationRunner(database).apply(SkyblockMigrations.getMigrations(database.dialect()));
        try (Connection conn = database.connection();
                Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO islands (id, owner_profile_id, owner_account_uuid) VALUES ('" + vessel.value()
                    + "', 'profile', '" + ada.value() + "')");
        }
        vessels = new SqlVesselsAdapter(database.dataSource());
        orders = new SqlMarketOrdersAdapter(database);
        voyages = new SqlVoyagesAdapter(database);
        vessels.add(vessel);
        epoch = ((IslandAuthorityOutcome.Success)
                        new RootAuthorityAdapter(database).acquire(VesselLease.rootOf(vessel), NODE, 30))
                .epoch();
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    @DisplayName("A sale writes the hold, the order, the trade and the standing together, or nothing")
    void aSaleIsWrittenWithItsHold() {
        Order sale = order(Kind.SALE, State.OWED, 200);

        assertThat(orders.recordSale(sale, new HoldWrite(epoch, OTHER, 1, bytes("after"))))
                .isFalse();
        assertThat(orders.recordSale(sale, new HoldWrite(epoch + 1, NODE, 1, bytes("after"))))
                .isFalse();
        assertThat(orders.recordSale(sale, new HoldWrite(epoch, NODE, 2, bytes("after"))))
                .isFalse();
        assertThat(orders.find(sale.id())).isEmpty();
        assertThat(vessels.cargo(vessel).orElseThrow().version()).isEqualTo(1);

        assertThat(orders.recordSale(sale, new HoldWrite(epoch, NODE, 1, bytes("after"))))
                .isTrue();

        assertThat(hold()).isEqualTo("after");
        assertThat(vessels.cargo(vessel).orElseThrow().version()).isEqualTo(2);
        assertThat(orders.find(sale.id())).contains(sale);
        assertThat(orders.tradeVolume(vessel)).isEqualTo(200);
        assertThat(orders.standing(vessel, "bay")).isEqualTo(200);
        assertThat(orders.standing(vessel, "cove")).isZero();
        assertThat(orders.open(vessel)).containsExactly(sale);
        assertThat(orders.vesselsWithOpenOrders()).containsExactly(vessel);
    }

    @Test
    @DisplayName("A purchase is delivered once, into the hold as it was read, and its state moves only on")
    void aPurchaseIsDeliveredOnce() {
        Order purchase = order(Kind.PURCHASE, State.PAYING, 500);
        orders.recordPurchase(purchase);
        orders.attempt(purchase.id(), 2);
        orders.attempt(purchase.id(), 1);
        assertThat(orders.find(purchase.id()).orElseThrow().attempts()).isEqualTo(2);

        assertThat(orders.deliver(purchase.id(), new HoldWrite(epoch, NODE, 7, bytes("bought"))))
                .isFalse();
        assertThat(orders.deliver(purchase.id(), new HoldWrite(epoch, NODE, 1, bytes("bought"))))
                .isTrue();
        assertThat(orders.deliver(purchase.id(), new HoldWrite(epoch, NODE, 2, bytes("twice"))))
                .isFalse();

        assertThat(hold()).isEqualTo("bought");
        assertThat(orders.find(purchase.id()).orElseThrow().state()).isEqualTo(State.DONE);
        assertThat(orders.standing(vessel, "bay")).isEqualTo(500);
        assertThat(orders.settle(purchase.id(), State.PAYING, State.CANCELLED)).isFalse();
        assertThat(orders.open(vessel)).isEmpty();
        assertThat(orders.vesselsWithOpenOrders()).isEmpty();
    }

    @Test
    @DisplayName("A purchase moved to a refund starts its attempts over, and is settled once")
    void aRefundStartsItsAttemptsOver() {
        Order purchase = order(Kind.PURCHASE, State.PAYING, 500);
        orders.recordPurchase(purchase);
        orders.attempt(purchase.id(), 3);

        assertThat(orders.settle(purchase.id(), State.PAYING, State.REFUNDING)).isTrue();
        assertThat(orders.find(purchase.id()).orElseThrow().attempts()).isZero();
        assertThat(orders.settle(purchase.id(), State.REFUNDING, State.REFUNDED))
                .isTrue();
        assertThat(orders.settle(purchase.id(), State.REFUNDING, State.REFUNDED))
                .isFalse();
        assertThat(orders.tradeVolume(vessel)).isZero();
    }

    @Test
    @DisplayName("A voyage is set under the lease and set again over the last one")
    void aVoyageIsSetUnderTheLease() {
        Instant arrives = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.SECONDS);

        assertThat(voyages.voyage(vessel)).isEmpty();
        assertThat(voyages.setSail(vessel, epoch, OTHER, new VoyagesPort.Voyage("bay", arrives)))
                .isFalse();
        assertThat(voyages.setSail(vessel, epoch, NODE, new VoyagesPort.Voyage("bay", arrives)))
                .isTrue();
        assertThat(voyages.voyage(vessel)).contains(new VoyagesPort.Voyage("bay", arrives));
        assertThat(voyages.setSail(vessel, epoch, NODE, new VoyagesPort.Voyage("cove", arrives.plusSeconds(5))))
                .isTrue();
        assertThat(voyages.voyage(vessel)).contains(new VoyagesPort.Voyage("cove", arrives.plusSeconds(5)));
    }

    private Order order(Kind kind, State state, long amount) {
        return new Order(UUID.randomUUID(), vessel, "bay", kind, "WHEAT", 10, amount, ada, state, 0);
    }

    private String hold() {
        return new String(vessels.cargo(vessel).orElseThrow().items(), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
