package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.gamemode.RootAuthorityPort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoJournalPort;
import com.uxplima.uxmskyblock.core.application.tradewinds.CargoTransfer;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselLease;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselService;
import com.uxplima.uxmskyblock.core.application.tradewinds.VesselsPort;
import com.uxplima.uxmskyblock.core.domain.gamemode.AuthorityRoot;
import com.uxplima.uxmskyblock.core.domain.gamemode.RootAuthorityRecord;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandAuthorityOutcome;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * The crew of a TradeWinds vessel opens its hold with {@code /is cargo} and moves stacks between the hold
 * and their inventories with a click, each move written as one operation over the player and the vessel.
 */
class TheCrewMovesCargoThroughTheHoldTest extends MockBukkitHarness {

    private static final int ROWS = 3;

    /** A role that may look into the hold and stow in it, and take nothing out. */
    private static final com.uxplima.uxmskyblock.core.domain.island.IslandRole DECKHAND =
            new com.uxplima.uxmskyblock.core.domain.island.IslandRole(
                    "DECKHAND",
                    10,
                    "Deckhand",
                    Set.of(
                            com.uxplima.uxmskyblock.core.domain.island.IslandPermission.VAULT_VIEW,
                            com.uxplima.uxmskyblock.core.domain.island.IslandPermission.VAULT_DEPOSIT),
                    false);

    private final IslandId vessel = IslandId.of(UUID.randomUUID());
    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private final Set<UUID> fenced = new HashSet<>();
    private final Set<UUID> sealed = new HashSet<>();
    private final Journal journal = new Journal();
    private byte[] cargo = new byte[0];
    private long cargoVersion = 1;
    private boolean aboard = true;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock ada;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock bo;

    @SuppressWarnings("NullAway.Init")
    private CargoHolds holds;

    @BeforeEach
    void setUpTheVessel() {
        ada = createPlayer("Ada");
        bo = createPlayer("Bo");
        for (PlayerMock player : List.of(ada, bo)) {
            sessions.put(
                    player.getUniqueId(),
                    new ActiveSession(new PlayerUuid(player.getUniqueId()), ProfileId.of(UUID.randomUUID()), 3L, 7L));
        }
        ada.getInventory().setItem(4, new ItemStack(Material.EMERALD, 16));
        holds = holds(ROWS);
    }

    @Test
    @DisplayName("A stack clicked in the inventory goes into the hold, written in one commit with the player")
    void aStackGoesIntoTheHold() {
        holds.open(ada);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(ada.getOpenInventory().title()))
                .isEqualTo("Cargo hold");

        click(ada, ROWS * 9 + slotInView(4));

        assertThat(count(ada, Material.EMERALD)).isZero();
        assertThat(held(Material.EMERALD)).isEqualTo(16);
        assertThat(cargoVersion).isEqualTo(2);
        assertThat(journal.events).containsExactly("intent", "applied 0", "commit");
        assertThat(top(ada).getItem(0)).isNotNull();
        assertThat(java.util.Objects.requireNonNull(sessions.get(ada.getUniqueId()))
                        .lastDurableVersion())
                .isEqualTo(9L);
    }

    @Test
    @DisplayName("A stack clicked in the hold comes out into the inventory, and the hold is emptied of it")
    void aStackComesOutOfTheHold() {
        stow(new ItemStack(Material.DIAMOND, 5));
        holds.open(ada);

        click(ada, 0);

        assertThat(count(ada, Material.DIAMOND)).isEqualTo(5);
        assertThat(held(Material.DIAMOND)).isZero();
        assertThat(top(ada).getItem(0)).isNull();
    }

    @Test
    @DisplayName("A commit refused moves nothing, and the player hears it in the hold's words")
    void aRefusedCommitMovesNothing() {
        journal.commit = InventoryMutationJournalOutcome.rejected("LEASE_EXPIRED");
        holds.open(ada);
        said(ada);

        click(ada, ROWS * 9 + slotInView(4));

        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
        assertThat(held(Material.EMERALD)).isZero();
        assertThat(journal.events).endsWith("commit", "abort");
        assertThat(said(ada)).contains("The move could not be saved");
        assertThat(holds.moving(ada.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("A member's role decides what they may move: this deckhand stows and takes nothing out")
    void theRoleDecides() {
        bo.getInventory().setItem(4, new ItemStack(Material.EMERALD, 16));
        stow(new ItemStack(Material.DIAMOND, 5));
        holds.open(bo);
        assertThat(window(bo)).isNotNull();

        click(bo, 0);
        assertThat(said(bo)).contains("does not allow");
        assertThat(count(bo, Material.DIAMOND)).isZero();
        assertThat(journal.events).isEmpty();
        assertThat(window(bo)).describedAs("a refused role keeps the window").isNotNull();

        click(bo, ROWS * 9 + slotInView(4));
        assertThat(count(bo, Material.EMERALD)).isZero();
        assertThat(held(Material.EMERALD)).isEqualTo(16);
    }

    @Test
    @DisplayName("Only the crew aboard opens the hold, and not while what they hold was made in a creative place")
    void onlyTheCrewAboard() {
        PlayerMock cy = createPlayer("Cy");
        sessions.put(
                cy.getUniqueId(),
                new ActiveSession(new PlayerUuid(cy.getUniqueId()), ProfileId.of(UUID.randomUUID()), 3L, 7L));
        holds.open(cy);
        assertThat(said(cy)).contains("Only the vessel's own crew");
        assertThat(window(cy)).isNull();

        aboard = false;
        holds.open(ada);
        assertThat(said(ada)).contains("not aboard a TradeWinds vessel");
        assertThat(window(ada)).isNull();

        aboard = true;
        sealed.add(ada.getUniqueId());
        holds.open(ada);
        assertThat(said(ada)).contains("Nothing goes into or out of a hold");
        assertThat(window(ada)).isNull();
    }

    @Test
    @DisplayName("A window left open after leaving the vessel moves nothing, and closes")
    void anOpenWindowIsAskedAgain() {
        holds.open(ada);
        said(ada);
        aboard = false;

        click(ada, ROWS * 9 + slotInView(4));

        assertThat(said(ada)).contains("not aboard a TradeWinds vessel");
        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
        assertThat(journal.events).isEmpty();
        assertThat(window(ada)).isNull();
    }

    @Test
    @DisplayName("A full hold takes nothing more, and the stack stays with the player")
    void aFullHoldTakesNothing() {
        holds = holds(1);
        Material[] kinds = {
            Material.STONE,
            Material.DIRT,
            Material.SAND,
            Material.GRAVEL,
            Material.OAK_LOG,
            Material.COBBLESTONE,
            Material.GLASS,
            Material.CLAY,
            Material.BRICKS
        };
        ItemStack[] full = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            full[i] = new ItemStack(kinds[i], 64);
        }
        cargo = BukkitInventorySerializer.serializeItemStacks(full);
        holds.open(ada);
        said(ada);

        click(ada, 9 + slotInView(4));

        assertThat(said(ada)).contains("no room for that");
        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
        assertThat(journal.events).isEmpty();
    }

    @Test
    @DisplayName("A stack another of the crew took first is not taken twice")
    void aStackIsNotTakenTwice() {
        stow(new ItemStack(Material.DIAMOND, 5));
        holds.open(ada);
        // Bo took the five and put back three: the slot holds something else than Ada saw.
        stow(new ItemStack(Material.DIAMOND, 3));
        cargoVersion++;
        said(ada);

        click(ada, 0);

        assertThat(said(ada)).contains("no longer where it was");
        assertThat(count(ada, Material.DIAMOND)).isZero();
        assertThat(journal.events).isEmpty();

        cargo = new byte[0];
        cargoVersion++;
        click(ada, 0);
        assertThat(said(ada)).contains("no longer where it was");
        assertThat(journal.events).isEmpty();
    }

    @Test
    @DisplayName("While a move is carried out the player's inventory holds still")
    @SuppressWarnings({"deprecation", "removal"})
    void theInventoryHoldsStill() {
        HoldStillListener listener = new HoldStillListener(holds::moving);
        List<Boolean> held = new ArrayList<>();
        journal.whileCommitting = () -> {
            org.bukkit.event.player.PlayerDropItemEvent thrown = new org.bukkit.event.player.PlayerDropItemEvent(
                    ada, org.mockito.Mockito.mock(org.bukkit.entity.Item.class));
            listener.onDrop(thrown);
            held.add(thrown.isCancelled());
        };
        holds.open(ada);

        click(ada, ROWS * 9 + slotInView(4));

        assertThat(held).containsExactly(true);
        assertThat(holds.moving(ada.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("The shipped file gives the hold three rows, and a hold that fits no chest falls back")
    void theHoldRows() throws Exception {
        assertThat(rows("hold { rows = 6 }")).isEqualTo(6);
        assertThat(rows("hold { rows = 7 }")).isEqualTo(3);
        assertThat(rows("hold { rows = 0 }")).isEqualTo(3);
        assertThat(rows("")).isEqualTo(3);
        assertThat(CargoHolds.keyOf("trade.not_saved")).isEqualTo("tradewinds.hold.not_saved");
        assertThat(CargoHolds.keyOf("tradewinds.hold.full")).isEqualTo("tradewinds.hold.full");
    }

    private static int rows(String hocon) throws Exception {
        return com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration.load(
                        org.spongepowered.configurate.hocon.HoconConfigurationLoader.builder()
                                .buildAndLoadString(hocon))
                .holdRows();
    }

    private CargoHolds holds(int rows) {
        ProfileId adaProfile = java.util.Objects.requireNonNull(sessions.get(ada.getUniqueId()))
                .activeProfileId();
        Island island = Island.create(
                        vessel,
                        IslandBounds.fromCenterAndRadius(0, 0, 100),
                        PlayerUuid.of(ada.getUniqueId()),
                        adaProfile,
                        Instant.now())
                .addMember(new com.uxplima.uxmskyblock.core.domain.island.IslandMember(
                        PlayerUuid.of(bo.getUniqueId()),
                        java.util.Objects.requireNonNull(sessions.get(bo.getUniqueId()))
                                .activeProfileId(),
                        DECKHAND,
                        Instant.now()));
        VesselsPort port = new VesselsPort() {
            @Override
            public Set<IslandId> findAll() {
                return Set.of(vessel);
            }

            @Override
            public boolean exists(IslandId islandId) {
                return vessel.equals(islandId);
            }

            @Override
            public void add(IslandId islandId) {}

            @Override
            public Optional<VesselsPort.Cargo> cargo(IslandId islandId) {
                return vessel.equals(islandId)
                        ? Optional.of(new VesselsPort.Cargo(cargo, cargoVersion))
                        : Optional.empty();
            }
        };
        VesselService service = new VesselService(port);
        service.start(vessel);
        server.getPluginManager().clearEvents();
        return new CargoHolds(
                service,
                port,
                new VesselLease(new Leases(), ServerNodeId.of("node-a"), Clock.systemUTC()),
                new CargoTransfer(journal, ServerNodeId.of("node-a")),
                location -> aboard ? Optional.of(island) : Optional.empty(),
                new CargoHolds.Sessions() {
                    @Override
                    public @Nullable ActiveSession session(UUID player) {
                        return sessions.get(player);
                    }

                    @Override
                    public void fence(UUID player, String why) {
                        fenced.add(player);
                    }
                },
                inline(),
                Messages.bundled(),
                player -> sealed.contains(player.getUniqueId()),
                rows);
    }

    private void stow(ItemStack item) {
        cargo = BukkitInventorySerializer.serializeItemStacks(new ItemStack[] {item});
    }

    private int held(Material material) {
        int total = 0;
        for (ItemStack item : BukkitInventorySerializer.deserializeItemStacks(cargo)) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /** The raw slot of a player inventory slot under the window: the grid first, then the hotbar. */
    private static int slotInView(int slot) {
        return slot < 9 ? 27 + slot : slot - 9;
    }

    private void click(PlayerMock player, int raw) {
        holds.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                raw,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
    }

    private static Inventory top(Player player) {
        return java.util.Objects.requireNonNull(player.getOpenInventory().getTopInventory());
    }

    private static CargoHolds.@Nullable View window(Player player) {
        @Nullable Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof CargoHolds.View view ? view : null;
    }

    private static int count(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static String said(PlayerMock player) {
        StringBuilder all = new StringBuilder();
        net.kyori.adventure.text.Component line;
        while ((line = player.nextComponentMessage()) != null) {
            all.append(PlainTextComponentSerializer.plainText().serialize(line)).append('\n');
        }
        return all.toString();
    }

    /** A lease every call of which this node wins. */
    private static final class Leases implements RootAuthorityPort {
        @Override
        public IslandAuthorityOutcome acquire(AuthorityRoot root, ServerNodeId node, int leaseSeconds) {
            return IslandAuthorityOutcome.success(1);
        }

        @Override
        public IslandAuthorityOutcome renew(
                AuthorityRoot root, ServerNodeId node, long expectedEpoch, int leaseSeconds) {
            return IslandAuthorityOutcome.success(expectedEpoch);
        }

        @Override
        public IslandAuthorityOutcome takeover(
                AuthorityRoot root, ServerNodeId newNode, long expectedEpoch, int leaseSeconds) {
            return IslandAuthorityOutcome.success(expectedEpoch + 1);
        }

        @Override
        public Optional<RootAuthorityRecord> find(AuthorityRoot root) {
            return Optional.empty();
        }
    }

    /** A journal that writes the hold on commit, as the real one does, and answers as the test says. */
    private final class Journal implements CargoJournalPort {
        private final List<String> events = new ArrayList<>();
        private InventoryMutationJournalOutcome commit = InventoryMutationJournalOutcome.success();
        private Runnable whileCommitting = () -> {};

        @Override
        public InventoryMutationJournalOutcome recordIntent(
                InventoryMutationOperationId operationId, ServerNodeId node, Move move, Duration expiry) {
            events.add("intent");
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public InventoryMutationJournalOutcome markApplied(InventoryMutationOperationId operationId, int index) {
            events.add("applied " + index);
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public InventoryMutationJournalOutcome commit(
                InventoryMutationOperationId operationId, ServerNodeId node, Move move) {
            events.add("commit");
            whileCommitting.run();
            if (commit.isSuccess()) {
                cargo = move.cargoAfter();
                cargoVersion++;
            }
            return commit;
        }

        @Override
        public InventoryMutationJournalOutcome abort(
                InventoryMutationOperationId operationId, ServerNodeId node, TradeJournalPort.Holder player) {
            events.add("abort");
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public List<InventoryMutationOperationId> findOpenMoves(ProfileId profile) {
            return List.of();
        }

        @Override
        public Optional<PlayerSide> playerSide(InventoryMutationOperationId operationId) {
            return Optional.empty();
        }

        @Override
        public Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId) {
            return Optional.empty();
        }

        @Override
        public InventoryMutationJournalOutcome settle(
                InventoryMutationOperationId operationId,
                ServerNodeId node,
                TradeJournalPort.Holder player,
                byte @Nullable [] restore,
                long restoreOver,
                InventoryMutationJournalState settledAs) {
            return InventoryMutationJournalOutcome.success();
        }
    }

    /** A scheduler that runs everything at once, on the calling thread. */
    private static SchedulerPort inline() {
        return new SchedulerPort() {
            @Override
            public void onGlobal(Runnable task) {
                task.run();
            }

            @Override
            public void onRegion(String worldName, int chunkX, int chunkZ, Runnable task) {
                task.run();
            }

            @Override
            public void onEntity(PlayerUuid playerUuid, Runnable task) {
                task.run();
            }

            @Override
            public boolean ownsEntity(PlayerUuid playerUuid) {
                return true;
            }

            @Override
            public void async(Runnable task) {
                task.run();
            }

            @Override
            public void asyncAfter(Duration delay, Runnable task) {
                task.run();
            }

            @Override
            public void laterGlobal(Duration delay, Runnable task) {
                task.run();
            }

            @Override
            public AutoCloseable repeatGlobal(Runnable task, Duration initialDelay, Duration period) {
                return () -> {};
            }

            @Override
            public AutoCloseable repeatAsync(Runnable task, Duration initialDelay, Duration period) {
                return () -> {};
            }
        };
    }
}
