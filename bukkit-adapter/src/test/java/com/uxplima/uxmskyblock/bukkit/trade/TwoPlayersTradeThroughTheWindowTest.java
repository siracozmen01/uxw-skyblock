package com.uxplima.uxmskyblock.bukkit.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.application.trade.TradeJournalPort;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalOutcome;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationJournalState;
import com.uxplima.uxmskyblock.core.domain.inventory.InventoryMutationOperationId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Two players ask, agree in the window and trade: what each offered moves to the other, and nothing
 * moves when the trade is called off or cannot be written.
 */
class TwoPlayersTradeThroughTheWindowTest extends MockBukkitHarness {

    private final Journal journal = new Journal();
    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private final Set<UUID> fenced = new HashSet<>();
    private final Set<UUID> keptAside = new HashSet<>();
    private long now = 1_000;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock ada;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock bo;

    @BeforeEach
    void setUpTraders() {
        ada = createPlayer("Ada");
        bo = createPlayer("Bo");
        for (PlayerMock player : List.of(ada, bo)) {
            sessions.put(
                    player.getUniqueId(),
                    new ActiveSession(new PlayerUuid(player.getUniqueId()), ProfileId.of(UUID.randomUUID()), 3L, 7L));
        }
        ada.getInventory().setItem(4, new ItemStack(Material.EMERALD, 16));
        bo.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
    }

    @Test
    @DisplayName("Asking and answering opens the window for both, and the question names the answer")
    void askingAndAnsweringOpensTheWindow() {
        Trades trades = trades(TradeConfiguration.defaultConfiguration());

        trades.ask(ada, bo);
        assertThat(said(bo)).contains("Ada wants to trade").contains("/is trade Ada");
        assertThat(window(ada)).isNull();

        trades.ask(bo, ada);
        assertThat(window(ada)).isNotNull();
        assertThat(window(bo)).isNotNull();
        assertThat(trades.trading(ada.getUniqueId())).isTrue();
    }

    @Test
    @DisplayName("The window's title and buttons are written as they are, without the chat prefix")
    void theWindowSpeaksWithoutThePrefix() {
        open();

        ItemStack ready = ada.getOpenInventory().getTopInventory().getItem(TradeWindow.READY);
        assertThat(ready).isNotNull();
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(java.util.Objects.requireNonNull(
                                ready.getItemMeta().displayName())))
                .isEqualTo("Click to agree to this trade.");
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(ada.getOpenInventory().title()))
                .isEqualTo("Trade with Bo");
    }

    @Test
    @DisplayName("Both agree and the trade happens: each gets what the other offered, and the windows close")
    void bothAgreeAndTheTradeHappens() {
        Trades trades = open();

        offer(ada, 4);
        offer(bo, 0);
        agree(ada);
        agree(bo);

        assertThat(count(ada, Material.EMERALD)).isZero();
        assertThat(count(ada, Material.DIAMOND)).isEqualTo(2);
        assertThat(count(bo, Material.EMERALD)).isEqualTo(16);
        assertThat(count(bo, Material.DIAMOND)).isZero();
        assertThat(journal.events).containsExactly("intent", "applied 0", "applied 1", "commit");
        assertThat(java.util.Objects.requireNonNull(sessions.get(ada.getUniqueId()))
                        .lastDurableVersion())
                .isEqualTo(9L);
        assertThat(window(ada)).isNull();
        assertThat(window(bo)).isNull();
        assertThat(trades.trading(ada.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("An offer shows on both sides, and changing it takes back both agreements")
    void changingAnOfferTakesBackAgreement() {
        open();

        offer(ada, 4);
        assertThat(tradeOf(ada).offers(ada.getUniqueId())).hasSize(1);
        assertThat(ada.getOpenInventory().getTopInventory().getItem(TradeWindow.MINE[0]))
                .isNotNull();
        assertThat(bo.getOpenInventory().getTopInventory().getItem(TradeWindow.THEIRS[0]))
                .isNotNull();
        agree(ada);
        assertThat(tradeOf(ada).ready(ada.getUniqueId())).isTrue();

        offer(bo, 0);
        assertThat(tradeOf(ada).ready(ada.getUniqueId())).isFalse();

        click(ada, TradeWindow.MINE[0]);
        assertThat(tradeOf(ada).offers(ada.getUniqueId())).isEmpty();
        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
    }

    @Test
    @DisplayName("A commit the database refuses leaves both inventories as they were and the window open")
    void aRefusedCommitMovesNothing() {
        open();
        journal.commit = InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");

        offer(ada, 4);
        offer(bo, 0);
        agree(ada);
        agree(bo);

        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
        assertThat(count(bo, Material.DIAMOND)).isEqualTo(2);
        assertThat(window(ada)).isNotNull();
        assertThat(tradeOf(ada).ready(ada.getUniqueId())).isFalse();
        assertThat(said(ada)).contains("could not be saved");
    }

    @Test
    @DisplayName("No room for what would be received stops the trade before anything is written")
    void noRoomStopsTheTrade() {
        open();
        for (int slot = 1; slot < 36; slot++) {
            bo.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }

        // Bo gives nothing back, and every one of his slots is full.
        offer(ada, 4);
        agree(ada);
        agree(bo);

        assertThat(journal.events).isEmpty();
        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
        assertThat(said(bo)).contains("no room");
    }

    @Test
    @DisplayName("Closing the window calls the trade off for both, and a player who leaves does too")
    void closingCallsItOff() {
        Trades trades = open();
        offer(ada, 4);

        trades.onClose(new InventoryCloseEvent(ada.getOpenInventory()));

        assertThat(trades.trading(ada.getUniqueId())).isFalse();
        assertThat(trades.trading(bo.getUniqueId())).isFalse();
        assertThat(window(bo)).isNull();
        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);

        Trades again = open();
        again.onLeave(bo.getUniqueId());
        assertThat(again.trading(ada.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("A player whose items are kept aside, one far away, or one trading already cannot be asked")
    void whoCannotTrade() {
        keptAside.add(bo.getUniqueId());
        Trades trades = trades(TradeConfiguration.defaultConfiguration());
        trades.ask(ada, bo);
        assertThat(said(ada)).contains("cannot trade while their items are kept aside");

        keptAside.clear();
        Trades near = trades(new TradeConfiguration(true, 60, 5, TradeConfiguration.Window.SHIPPED));
        ada.teleport(new Location(ada.getWorld(), 0, 64, 0));
        bo.teleport(new Location(ada.getWorld(), 100, 64, 0));
        near.ask(ada, bo);
        assertThat(said(ada)).contains("too far");

        Trades busy = open();
        PlayerMock cy = createPlayer("Cy");
        sessions.put(
                cy.getUniqueId(),
                new ActiveSession(new PlayerUuid(cy.getUniqueId()), ProfileId.of(UUID.randomUUID()), 1L, 1L));
        busy.ask(cy, ada);
        assertThat(said(cy)).contains("trading with somebody else");
    }

    @Test
    @DisplayName("A question lapses: answering after it lapsed asks again instead of opening the window")
    void aQuestionLapses() {
        Trades trades = trades(TradeConfiguration.defaultConfiguration());
        trades.ask(ada, bo);
        now += Duration.ofSeconds(61).toNanos();

        trades.ask(bo, ada);

        assertThat(window(bo)).isNull();
        assertThat(said(ada)).contains("Bo wants to trade");
    }

    @Test
    @DisplayName("What a player receives goes into their pockets only: full pockets are no room, bare armour or not")
    void receivedItemsGoIntoStorage() {
        open();
        ada.getInventory().setItem(4, new ItemStack(Material.IRON_HELMET));
        bo.getInventory().setHelmet(null);
        for (int slot = 1; slot < 36; slot++) {
            bo.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }

        offer(ada, 4);
        agree(ada);
        agree(bo);

        assertThat(said(bo)).contains("no room");
        assertThat(count(bo, Material.IRON_HELMET)).isZero();
        assertThat(bo.getInventory().getHelmet()).isNull();
        assertThat(count(ada, Material.IRON_HELMET)).isEqualTo(1);
    }

    @Test
    @DisplayName("While a trade is carried out nothing changes what its players carry: no throw, no pickup, no click")
    @SuppressWarnings({"deprecation", "removal"})
    void anInventoryHoldsStillWhileItIsTraded() {
        Trades trades = open();
        com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener listener =
                new com.uxplima.uxmskyblock.bukkit.inventory.HoldStillListener(trades::exchanging);
        List<Boolean> held = new ArrayList<>();
        journal.whileCommitting = () -> {
            org.bukkit.event.player.PlayerDropItemEvent thrown = new org.bukkit.event.player.PlayerDropItemEvent(
                    bo, org.mockito.Mockito.mock(org.bukkit.entity.Item.class));
            listener.onDrop(thrown);
            held.add(thrown.isCancelled());
            org.bukkit.event.entity.EntityDamageEvent hurt = new org.bukkit.event.entity.EntityDamageEvent(
                    ada, org.bukkit.event.entity.EntityDamageEvent.DamageCause.FALL, 4.0);
            listener.onHurt(hurt);
            held.add(hurt.isCancelled());
            // Ada opens another chest while the trade is carried out and reaches into it.
            ada.openInventory(server.createInventory(null, 9));
            InventoryClickEvent moved = new InventoryClickEvent(
                    ada.getOpenInventory(),
                    InventoryType.SlotType.CONTAINER,
                    0,
                    ClickType.LEFT,
                    InventoryAction.PICKUP_ALL);
            listener.onClick(moved);
            held.add(moved.isCancelled());
        };

        offer(ada, 4);
        offer(bo, 0);
        agree(ada);
        agree(bo);

        assertThat(held).containsExactly(true, true, true);
        assertThat(trades.exchanging(ada.getUniqueId())).isFalse();
        org.bukkit.event.player.PlayerDropItemEvent after = new org.bukkit.event.player.PlayerDropItemEvent(
                bo, org.mockito.Mockito.mock(org.bukkit.entity.Item.class));
        listener.onDrop(after);
        assertThat(after.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("A side that changed while the commit was refused is not written back over: its player leaves")
    void aChangedSideIsNotPutBackOver() {
        open();
        journal.commit = InventoryMutationJournalOutcome.rejected("OCC_VERSION_MISMATCH");
        journal.whileCommitting = () -> bo.getInventory().setItem(20, new ItemStack(Material.GOLD_INGOT, 1));

        offer(ada, 4);
        offer(bo, 0);
        agree(ada);
        agree(bo);

        assertThat(fenced).containsExactly(bo.getUniqueId());
        assertThat(count(ada, Material.EMERALD)).describedAs("Ada is put back").isEqualTo(16);
        assertThat(count(bo, Material.GOLD_INGOT))
                .describedAs("Bo is left as he is, for recovery to settle")
                .isEqualTo(1);
    }

    private Trades open() {
        Trades trades = trades(TradeConfiguration.defaultConfiguration());
        trades.ask(ada, bo);
        trades.ask(bo, ada);
        return trades;
    }

    private Trades trades(TradeConfiguration config) {
        Trades trades = new Trades(
                config,
                Messages.bundled(),
                inline(),
                new Trades.Sessions() {
                    @Override
                    public @Nullable ActiveSession session(UUID player) {
                        return sessions.get(player);
                    }

                    @Override
                    public void fence(UUID player, String why) {
                        fenced.add(player);
                    }
                },
                new TradeExchange(journal, ServerNodeId.of("node-a")),
                player -> keptAside.contains(player.getUniqueId()),
                () -> now);
        server.getPluginManager().clearEvents();
        listening = trades;
        return trades;
    }

    @SuppressWarnings("NullAway.Init")
    private Trades listening;

    private void offer(PlayerMock player, int slot) {
        click(player, TradeWindow.ROWS * 9 + slotInView(slot));
    }

    /** The raw slot of a player inventory slot under a six row window: the grid first, then the hotbar. */
    private static int slotInView(int slot) {
        return slot < 9 ? 27 + slot : slot - 9;
    }

    private void agree(PlayerMock player) {
        click(player, TradeWindow.READY);
    }

    private void click(PlayerMock player, int raw) {
        listening.onClick(new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                raw,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL));
    }

    private static TradeWindow.@Nullable View window(Player player) {
        org.bukkit.inventory.@Nullable Inventory top = player.getOpenInventory().getTopInventory();
        return top != null && top.getHolder() instanceof TradeWindow.View view ? view : null;
    }

    private static Trade tradeOf(Player player) {
        return java.util.Objects.requireNonNull(window(player), "no trade window open")
                .trade();
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

    /** A journal that records what it was asked and answers as the test says. */
    private static final class Journal implements TradeJournalPort {
        private final List<String> events = new ArrayList<>();
        private InventoryMutationJournalOutcome commit = InventoryMutationJournalOutcome.success();
        private Runnable whileCommitting = () -> {};

        @Override
        public InventoryMutationJournalOutcome recordIntent(
                InventoryMutationOperationId operationId,
                ServerNodeId node,
                List<Side> sides,
                String payload,
                Duration expiry) {
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
                InventoryMutationOperationId operationId, ServerNodeId node, List<Outcome> sides) {
            events.add("commit");
            whileCommitting.run();
            return commit;
        }

        @Override
        public InventoryMutationJournalOutcome abort(
                InventoryMutationOperationId operationId, ServerNodeId node, List<Holder> holders) {
            events.add("abort");
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public List<InventoryMutationOperationId> findOpenTrades(ProfileId profile) {
            return List.of();
        }

        @Override
        public List<Participant> participants(InventoryMutationOperationId operationId) {
            return List.of();
        }

        @Override
        public Optional<InventoryMutationJournalState> state(InventoryMutationOperationId operationId) {
            return Optional.empty();
        }

        @Override
        public InventoryMutationJournalOutcome settleSide(
                InventoryMutationOperationId operationId,
                int index,
                ServerNodeId node,
                Holder holder,
                byte @Nullable [] restore,
                long restoreOver,
                boolean closeTrade) {
            return InventoryMutationJournalOutcome.success();
        }

        @Override
        public InventoryMutationJournalOutcome settleTrade(
                InventoryMutationOperationId operationId,
                ServerNodeId node,
                Holder holder,
                InventoryMutationJournalState settledAs) {
            return InventoryMutationJournalOutcome.success();
        }
    }
}
