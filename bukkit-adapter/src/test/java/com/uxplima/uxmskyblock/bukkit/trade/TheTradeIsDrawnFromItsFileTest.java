package com.uxplima.uxmskyblock.bukkit.trade;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.uxplima.uxmlib.menu.providers.ContentProvider;
import com.uxplima.uxmlib.menu.providers.OwnRowsClick;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.runtime.MenuHolder;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmlib.menu.spec.ContentRegionSpec;
import com.uxplima.uxmskyblock.bukkit.config.TradeConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.session.ActiveSession;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.trade.TradeExchange;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A trade is drawn from {@code menus/player-trade.conf}: the window, its buttons and where each offer
 * shows are the operator's, and the offers stay on the trade, each stack in its owner's inventory
 * until both agree.
 *
 * <p>The window was built in code, so an operator could not move a button, recolour the agreement or
 * lay the two offers out the way the players of their server know from elsewhere.
 */
class TheTradeIsDrawnFromItsFileTest extends MockBukkitHarness {

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final Map<UUID, ActiveSession> sessions = new HashMap<>();
    private final TwoPlayersTradeThroughTheWindowTest.Journal journal =
            new TwoPlayersTradeThroughTheWindowTest.Journal();

    @SuppressWarnings("NullAway.Init")
    private PlayerMock ada;

    @SuppressWarnings("NullAway.Init")
    private PlayerMock bo;

    @SuppressWarnings("NullAway.Init")
    private Trades trades;

    @SuppressWarnings("NullAway.Init")
    private SkyblockMenuEngine engine;

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
        trades = new Trades(
                TradeConfiguration.defaultConfiguration(),
                Messages.bundled(),
                TwoPlayersTradeThroughTheWindowTest.inline(),
                new Trades.Sessions() {
                    @Override
                    public @Nullable ActiveSession session(UUID player) {
                        return sessions.get(player);
                    }

                    @Override
                    public void fence(UUID player, String why) {}
                },
                new TradeExchange(journal, ServerNodeId.of("node-a")),
                Predicate.not(player -> true),
                () -> 1_000L);
    }

    /** The engine a server builds over {@code files}, its listener and the trade's own installed as there. */
    private void useFile(SkyblockMenuEngine files) {
        engine = files;
        trades.useMenuEngine(engine);
        engine.install();
        Plugin plugin = MockBukkit.createMockPlugin();
        server.getPluginManager().registerEvents(new TradeListener(trades), plugin);
    }

    private void useShippedFile() {
        useFile(ShippedTemplates.engineWith(dataDir, "player-trade.conf"));
    }

    /** Ada asks, Bo answers, and both windows are up. */
    private void open() {
        trades.ask(ada, bo);
        trades.ask(bo, ada);
        settle(() -> windowOf(ada) != null && windowOf(bo) != null);
    }

    /** Runs the server until {@code done} holds: the engine draws a window off the player's thread first. */
    private void settle(java.util.function.BooleanSupplier done) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            if (done.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the server never settled");
    }

    /** A few ticks, for what is scheduled but awaited by nothing a test can name. */
    private void ticks() {
        server.getScheduler().performTicks(5);
    }

    private static @Nullable Inventory windowOf(PlayerMock player) {
        Inventory top = player.getOpenInventory().getTopInventory();
        return TradeWindow.viewOf(top) != null ? top : null;
    }

    private static Inventory openWindow(PlayerMock player) {
        return Objects.requireNonNull(windowOf(player), player.getName() + " has no trade window");
    }

    private void click(PlayerMock player, int raw) {
        server.getPluginManager().callEvent(new ServerClick(player.getOpenInventory(), raw));
        ticks();
    }

    /**
     * A left click that reads the clicked stack as a server does. MockBukkit names the right slot of
     * the player's inventory for a raw slot below the window, and then reads the stack of another, so
     * a click on a stack there would arrive as a click on nothing.
     */
    private static final class ServerClick extends InventoryClickEvent {

        ServerClick(org.bukkit.inventory.InventoryView view, int raw) {
            super(view, InventoryType.SlotType.CONTAINER, raw, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        }

        @Override
        public @Nullable ItemStack getCurrentItem() {
            if (getRawSlot() < getView().getTopInventory().getSize()) {
                return super.getCurrentItem();
            }
            return getView().getBottomInventory().getItem(getSlot());
        }
    }

    /** The raw slot of a player inventory slot under a six row window: the grid first, then the hotbar. */
    private static int ownRow(int slot) {
        return 54 + (slot < 9 ? 27 + slot : slot - 9);
    }

    private static ContentRegionSpec region(String id) {
        return Objects.requireNonNull(
                ShippedTemplates.spec("player-trade.conf").contents().get(id));
    }

    private static int firstSlotOf(String id) {
        return region(id).slots().slots().get(0);
    }

    private static int count(PlayerMock player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    @Test
    @DisplayName("The shipped file holds both offers whole and lets neither be moved by hand")
    void theFileHoldsBothOffers() {
        for (String id : List.of(TradeWindow.MINE_REGION, TradeWindow.THEIRS_REGION)) {
            assertThat(region(id).slots().slots()).hasSize(Trade.MOST_OFFERS);
            assertThat(region(id).editable()).isFalse();
        }
    }

    @Test
    @DisplayName("Answering opens the file for both, titled with the other player's name")
    void answeringOpensTheFile() {
        useShippedFile();

        open();

        assertThat(openWindow(ada).getHolder()).isInstanceOf(MenuHolder.class);
        assertThat(openWindow(bo).getHolder()).isInstanceOf(MenuHolder.class);
        assertThat(PlainTextComponentSerializer.plainText()
                        .serialize(ada.getOpenInventory().title())
                        .strip())
                .isEqualTo("Trade with Bo");
    }

    @Test
    @DisplayName("A click on a stack of one's own offers it, and the other player sees it in their half")
    void aClickOnOwnStackOffersIt() {
        useShippedFile();
        open();

        click(ada, ownRow(4));

        assertThat(openWindow(ada).getItem(firstSlotOf(TradeWindow.MINE_REGION)))
                .isEqualTo(new ItemStack(Material.EMERALD, 16));
        assertThat(openWindow(bo).getItem(firstSlotOf(TradeWindow.THEIRS_REGION)))
                .isEqualTo(new ItemStack(Material.EMERALD, 16));
        assertThat(count(ada, Material.EMERALD))
                .describedAs("an offer stays in its owner's inventory")
                .isEqualTo(16);
    }

    @Test
    @DisplayName("A click on one's own offer takes it back, and a click on the other's offer does nothing")
    void aClickOnOwnOfferTakesItBack() {
        useShippedFile();
        open();
        click(ada, ownRow(4));
        click(bo, ownRow(0));

        click(ada, firstSlotOf(TradeWindow.THEIRS_REGION));
        assertThat(openWindow(ada).getItem(firstSlotOf(TradeWindow.MINE_REGION)))
                .describedAs("a click on the other's offer leaves one's own alone")
                .isEqualTo(new ItemStack(Material.EMERALD, 16));
        click(ada, firstSlotOf(TradeWindow.MINE_REGION));

        assertThat(openWindow(ada).getItem(firstSlotOf(TradeWindow.MINE_REGION)))
                .isNull();
        assertThat(openWindow(ada).getItem(firstSlotOf(TradeWindow.THEIRS_REGION)))
                .isEqualTo(new ItemStack(Material.DIAMOND, 2));
        assertThat(openWindow(bo).getItem(firstSlotOf(TradeWindow.THEIRS_REGION)))
                .isNull();
    }

    @Test
    @DisplayName("Agreeing shows on both sides, and when both agree the trade happens and the windows close")
    void bothAgreeAndTheTradeHappens() {
        useShippedFile();
        open();
        click(ada, ownRow(4));
        click(bo, ownRow(0));

        click(ada, 0);

        assertThat(Objects.requireNonNull(openWindow(ada).getItem(0)).getType()).isEqualTo(Material.LIME_CONCRETE);
        assertThat(Objects.requireNonNull(openWindow(bo).getItem(8)).getType())
                .isEqualTo(Material.LIME_STAINED_GLASS_PANE);

        click(bo, 0);

        assertThat(count(ada, Material.DIAMOND)).isEqualTo(2);
        assertThat(count(ada, Material.EMERALD)).isZero();
        assertThat(count(bo, Material.EMERALD)).isEqualTo(16);
        assertThat(count(bo, Material.DIAMOND)).isZero();
        assertThat(windowOf(ada)).isNull();
        assertThat(windowOf(bo)).isNull();
        assertThat(trades.trading(ada.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("The button that calls the trade off closes both windows and moves nothing")
    void callingItOffClosesBoth() {
        useShippedFile();
        open();
        click(ada, ownRow(4));

        click(bo, 4);

        assertThat(windowOf(ada)).isNull();
        assertThat(windowOf(bo)).isNull();
        assertThat(trades.trading(ada.getUniqueId())).isFalse();
        assertThat(count(ada, Material.EMERALD)).isEqualTo(16);
    }

    @Test
    @DisplayName("Closing the window calls the trade off for both")
    void closingCallsItOff() {
        useShippedFile();
        open();

        ada.closeInventory();
        ticks();

        assertThat(windowOf(bo)).isNull();
        assertThat(trades.trading(bo.getUniqueId())).isFalse();
    }

    @Test
    @DisplayName("A window reopened without its trade shows no offer and takes none")
    void aWindowWithNoTradeTakesNothing() {
        useShippedFile();
        ContentProvider mine =
                engine.bindings().contents().get(TradeWindow.MINE_REGION).orElseThrow();
        MenuContext tradeless = MenuContext.of(ada, null, 0);

        assertThat(mine.render(tradeless, region(TradeWindow.MINE_REGION))).isEmpty();
        assertThat(mine.ownRowsClicked(
                        tradeless,
                        region(TradeWindow.MINE_REGION),
                        new OwnRowsClick(4, new ItemStack(Material.EMERALD, 16), ClickKind.LEFT)))
                .isFalse();
    }

    @Test
    @DisplayName("A file whose own half cannot hold a whole offer leaves the trade in the window built in code")
    void aShortOwnHalfFallsBack() throws Exception {
        useFile(fileWith("[\"9-12\"]", "[\"14-17\", \"23-26\", \"32-35\", \"41-44\", \"50-53\"]"));

        open();

        assertThat(openWindow(ada).getHolder()).isInstanceOf(TradeWindow.View.class);
    }

    @Test
    @DisplayName("A file whose other half cannot show a whole offer leaves the trade in the window built in code")
    void aShortOtherHalfFallsBack() throws Exception {
        useFile(fileWith("[\"9-12\", \"18-21\", \"27-30\", \"36-39\", \"45-48\"]", "[\"14-17\"]"));

        open();

        assertThat(openWindow(ada).getHolder()).isInstanceOf(TradeWindow.View.class);
    }

    @Test
    @DisplayName("A file whose other half a player could fill by hand leaves the trade in the window built in code")
    void anEditableOtherHalfFallsBack() throws Exception {
        String shipped = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("menus/player-trade.conf"))
                        .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        int theirs = shipped.indexOf("\"skyblock:trade-theirs\"");
        String edited = shipped.substring(0, theirs)
                + shipped.substring(theirs).replaceFirst("editable = false", "editable = true");
        Path menus = Files.createDirectories(dataDir.resolve("menus"));
        Files.writeString(menus.resolve("player-trade.conf"), edited);
        SkyblockMenuEngine files = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        files.loadSpecs();
        useFile(files);

        open();

        assertThat(openWindow(ada).getHolder()).isInstanceOf(TradeWindow.View.class);
    }

    /** An engine over a trade file whose two halves are the slots given. */
    private SkyblockMenuEngine fileWith(String mine, String theirs) throws java.io.IOException {
        Path menus = Files.createDirectories(dataDir.resolve("menus"));
        Files.writeString(
                menus.resolve("player-trade.conf"),
                "rows = 6\ncontent {\n  \"skyblock:trade-mine\" { slots = " + mine + ", editable = false }\n"
                        + "  \"skyblock:trade-theirs\" { slots = " + theirs + ", editable = false }\n}\n"
                        + "items { cancel { slot = 4, material = BARRIER, click { any = [\"skyblock:trade-cancel\"] } } }\n");
        SkyblockMenuEngine files = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        files.loadSpecs();
        return files;
    }

    @Test
    @DisplayName("A file whose offers a player could fill by hand leaves the trade in the window built in code")
    void aFileWithAnEditableOfferFallsBack() throws Exception {
        String shipped = new String(
                Objects.requireNonNull(getClass().getClassLoader().getResourceAsStream("menus/player-trade.conf"))
                        .readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        Path menus = Files.createDirectories(dataDir.resolve("menus"));
        Files.writeString(
                menus.resolve("player-trade.conf"), shipped.replaceFirst("editable = false", "editable = true"));
        SkyblockMenuEngine files = new SkyblockMenuEngine(MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        files.loadSpecs();
        useFile(files);

        open();

        assertThat(openWindow(ada).getHolder()).isInstanceOf(TradeWindow.View.class);
    }
}
