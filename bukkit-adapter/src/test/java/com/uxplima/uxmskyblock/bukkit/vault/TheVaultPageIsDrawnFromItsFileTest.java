package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmlib.menu.providers.ContentClick;
import com.uxplima.uxmlib.menu.providers.ContentProvider;
import com.uxplima.uxmlib.menu.runtime.MenuContext;
import com.uxplima.uxmlib.menu.runtime.MenuHolder;
import com.uxplima.uxmlib.menu.spec.ClickKind;
import com.uxplima.uxmlib.menu.spec.ContentRegionSpec;
import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.menu.ShippedTemplates;
import com.uxplima.uxmskyblock.bukkit.menu.SkyblockMenuEngine;
import com.uxplima.uxmskyblock.bukkit.session.PlayerSessionCoordinator;
import com.uxplima.uxmskyblock.bukkit.session.WritesPlayerStateItself;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.island.Island;
import com.uxplima.uxmskyblock.core.domain.island.IslandBounds;
import com.uxplima.uxmskyblock.core.domain.vault.VaultEditSession;
import com.uxplima.uxmskyblock.core.domain.vault.VaultPage;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionId;
import com.uxplima.uxmskyblock.core.domain.vault.VaultSessionState;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

/**
 * A vault page is drawn from {@code menus/island-vault-page.conf}: the window around the page is the
 * operator's, and the page is the region the file names.
 *
 * <p>The page was a plain chest built in code, so an operator could not give it a way back, a way to
 * the next page or a word about what the player's role allows.
 */
class TheVaultPageIsDrawnFromItsFileTest extends MockBukkitHarness {

    /** Seven diamonds, made once the server is up: an item cannot be made before it. */
    private static ItemStack diamonds() {
        return new ItemStack(Material.DIAMOND, 7);
    }

    @TempDir
    @SuppressWarnings("NullAway.Init")
    Path dataDir;

    private final IslandVaultService vaultService = mock(IslandVaultService.class);
    private final IslandStoragePort storage = mock(IslandStoragePort.class);
    private final PlayerSessionCoordinator sessions = mock(PlayerSessionCoordinator.class);
    private final IslandId islandId = IslandId.of(UUID.randomUUID());
    private final ProfileId profileId = new ProfileId(UUID.randomUUID());
    private PlayerMock player;
    private IslandVaultWindow window;

    @BeforeEach
    void setUp() {
        player = createPlayer("Keeper");
        when(sessions.activeProfile(player.getUniqueId())).thenReturn(Optional.of(profileId));
        Island island = Island.create(
                islandId,
                IslandBounds.fromCenterAndRadius(0, 0, 100),
                new PlayerUuid(player.getUniqueId()),
                profileId,
                Instant.now());
        when(storage.findIslandIdByProfileId(profileId)).thenReturn(Optional.of(islandId));
        when(storage.findIslandById(islandId)).thenReturn(Optional.of(island));
        when(vaultService.getMaxAllowedPages(islandId)).thenReturn(3);
        window = new IslandVaultWindow(
                vaultService,
                storage,
                scheduler(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                sessions);
    }

    /** Leases a page that holds {@code stored}, slot by slot. */
    private void pageHolds(int page, @Nullable ItemStack... stored) {
        VaultPage vaultPage = new VaultPage(
                islandId,
                page,
                1,
                1,
                null,
                BukkitInventorySerializer.serializeItemStacks(stored),
                "test",
                Instant.now());
        VaultEditSession session = new VaultEditSession(
                VaultSessionId.random(),
                islandId,
                page,
                player.getUniqueId(),
                1,
                1,
                VaultSessionState.ACTIVE,
                null,
                Instant.now(),
                Instant.now().plusSeconds(60),
                null);
        when(vaultService.openVaultPage(any(), eq(profileId), any(), eq(page), any(), any()))
                .thenReturn(new IslandVaultService.VaultOpenResult(vaultPage, session));
    }

    private SkyblockMenuEngine fileEngine() {
        SkyblockMenuEngine engine = ShippedTemplates.engineWith(dataDir, "island-vault-page.conf");
        window.useMenuEngine(engine);
        // The listener a server installs, so a close reads the region back as it does there.
        engine.install();
        return engine;
    }

    private Inventory openPage(int page) {
        window.open(player, page);
        return pageOpen(page);
    }

    /** The window of {@code page}, once the engine has drawn it: it reads its lists off the player's thread. */
    private Inventory pageOpen(int page) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            server.getScheduler().performOneTick();
            Inventory top = player.getOpenInventory().getTopInventory();
            if (IslandVaultWindow.VaultHolder.of(top)
                    .filter(holder -> holder.page() == page)
                    .isPresent()) {
                return Objects.requireNonNull(top);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("page " + page + " never opened");
    }

    private ContentProvider provider(SkyblockMenuEngine engine) {
        return engine.bindings().contents().get(IslandVaultWindow.PAGE).orElseThrow();
    }

    private static ContentRegionSpec region() {
        return Objects.requireNonNull(
                ShippedTemplates.spec("island-vault-page.conf").contents().get(IslandVaultWindow.PAGE));
    }

    private static MenuContext contextOf(Inventory top) {
        return ((MenuHolder) Objects.requireNonNull(top.getHolder())).ctx();
    }

    @Test
    @DisplayName("The shipped file holds a page of the size a page has, so the vault opens in it")
    void theFileHoldsAWholePage() {
        assertThat(region().slots().slots())
                .hasSize(VaultConfiguration.defaultConfiguration().slotsPerPage());
        assertThat(region().editable()).isTrue();
    }

    @Test
    @DisplayName("Opening a page draws it in the file, the stored items in the region and the page tile under it")
    void openingDrawsThePageInTheFile() {
        fileEngine();
        pageHolds(1, diamonds());

        Inventory top = openPage(1);

        assertThat(top.getHolder()).isInstanceOf(MenuHolder.class);
        assertThat(top.getItem(region().slots().slots().get(0))).isEqualTo(diamonds());
        assertThat(top.getItem(49)).isNotNull();
        assertThat(Objects.requireNonNull(top.getItem(49)).getType()).isEqualTo(Material.CHEST);
        assertThat(contextOf(top).arguments())
                .containsEntry("page", "1")
                .containsEntry("pages", "3")
                .containsEntry("next_page", "2")
                .containsEntry("pages_after", "2")
                .containsEntry("access", "<key:menu.vault_page.access_both>");
    }

    @Test
    @DisplayName("The checkpoint and the stop find the page behind a window drawn from the file")
    void theHolderIsFoundBehindTheFile() {
        fileEngine();
        pageHolds(1, diamonds());

        Inventory top = openPage(1);

        assertThat(IslandVaultWindow.VaultHolder.of(top)).isPresent();
        assertThat(WritesPlayerStateItself.of(top))
                .containsSame(IslandVaultWindow.VaultHolder.of(top).orElseThrow());
    }

    @Test
    @DisplayName("A role that may not take out is refused once for a gesture, however many slots it is asked about")
    void aRefusalIsSaidOnce() {
        ContentProvider provider = provider(fileEngine());
        IslandVaultWindow.VaultHolder holder = holder(false, true);
        MenuContext ctx = MenuContext.of(player, holder, 0);

        for (int slot = 0; slot < 3; slot++) {
            ContentClick take = new ContentClick(slot, slot, ContentClick.Kind.TAKE, null, diamonds());
            assertThat(provider.allows(ctx, region(), take)).isFalse();
        }

        assertThat(player.nextMessage()).contains("cannot take anything");
        assertThat(player.nextMessage()).isNull();
    }

    @Test
    @DisplayName("A role that may not put in is refused an insert and a swap, and a take is allowed")
    void theRoleDecidesWhatMoves() {
        ContentProvider provider = provider(fileEngine());
        MenuContext ctx = MenuContext.of(player, holder(true, false), 0);

        assertThat(provider.allows(ctx, region(), new ContentClick(0, 0, ContentClick.Kind.TAKE, null, diamonds())))
                .isTrue();
        assertThat(provider.allows(ctx, region(), new ContentClick(0, 0, ContentClick.Kind.INSERT, diamonds(), null)))
                .isFalse();
        assertThat(provider.allows(
                        ctx, region(), new ContentClick(0, 0, ContentClick.Kind.SWAP, diamonds(), diamonds())))
                .isFalse();
    }

    @Test
    @DisplayName("Closing writes the page as the region holds it, and a second close writes nothing")
    void closingWritesThePageOnce() {
        ContentProvider provider = provider(fileEngine());
        IslandVaultWindow.VaultHolder holder = holder(true, true);
        MenuContext ctx = MenuContext.of(player, holder, 0);
        List<@Nullable ItemStack> contents = new ArrayList<>(Arrays.asList(new ItemStack[45]));
        contents.set(3, diamonds());

        provider.readBack(ctx, region(), contents);
        provider.readBack(ctx, region(), contents);

        ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
        verify(vaultService, times(1)).commitVaultPage(any(), written.capture(), any(), any(), any());
        assertThat(BukkitInventorySerializer.deserializeItemStacks(written.getValue())[3])
                .isEqualTo(diamonds());
    }

    @Test
    @DisplayName("A page written at a stop is not written again when its window closes after")
    void theStopWritesFirstAndTheCloseNothing() {
        SkyblockMenuEngine engine = fileEngine();
        pageHolds(1, diamonds());
        Inventory top = openPage(1);

        window.writeBeforeStop(player);
        verify(vaultService, times(1)).commitVaultPage(any(), any(), any(), any(), any());
        provider(engine).readBack(contextOf(top), region(), new ArrayList<>(Arrays.asList(new ItemStack[45])));

        verify(vaultService, times(1)).commitVaultPage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("The chest built in code is written once too, by the stop or the close, whichever comes first")
    void theChestBuiltInCodeIsWrittenOnce() {
        pageHolds(1, diamonds());
        Inventory top = openPage(1);

        window.writeBeforeStop(player);
        verify(vaultService, times(1)).commitVaultPage(any(), any(), any(), any(), any());
        new IslandVaultListener(window)
                .onInventoryClose(new org.bukkit.event.inventory.InventoryCloseEvent(player.getOpenInventory()));

        assertThat(top.getHolder()).isInstanceOf(IslandVaultWindow.VaultHolder.class);
        verify(vaultService, times(1)).commitVaultPage(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("The chest built in code keeps what a larger page held past it when it is written")
    void theChestBuiltInCodeKeepsTheTail() {
        @Nullable ItemStack[] stored = new ItemStack[54];
        stored[50] = new ItemStack(Material.EMERALD, 2);
        pageHolds(1, stored);
        openPage(1);

        new IslandVaultListener(window)
                .onInventoryClose(new org.bukkit.event.inventory.InventoryCloseEvent(player.getOpenInventory()));

        ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
        verify(vaultService).commitVaultPage(any(), written.capture(), any(), any(), any());
        assertThat(BukkitInventorySerializer.deserializeItemStacks(written.getValue())[50])
                .isEqualTo(new ItemStack(Material.EMERALD, 2));
    }

    @Test
    @DisplayName("A page that held more slots than a page has now keeps what lies past them when it is written")
    void aLargerPageKeepsItsTail() {
        SkyblockMenuEngine engine = fileEngine();
        @Nullable ItemStack[] stored = new ItemStack[54];
        stored[0] = diamonds();
        stored[50] = new ItemStack(Material.EMERALD, 2);
        pageHolds(1, stored);
        Inventory top = openPage(1);
        List<@Nullable ItemStack> contents = new ArrayList<>(Arrays.asList(new ItemStack[45]));

        provider(engine).readBack(contextOf(top), region(), contents);

        ArgumentCaptor<byte[]> written = ArgumentCaptor.forClass(byte[].class);
        verify(vaultService).commitVaultPage(any(), written.capture(), any(), any(), any());
        @Nullable ItemStack[] page = BukkitInventorySerializer.deserializeItemStacks(written.getValue());
        assertThat(page[0]).describedAs("taken out in the window").isNull();
        assertThat(page[50]).describedAs("past the window, kept").isEqualTo(new ItemStack(Material.EMERALD, 2));
    }

    @Test
    @DisplayName("A file whose page region is not the size of a page leaves the vault in the chest built in code")
    void aFileOfAnotherSizeFallsBack() throws Exception {
        Path menus = Files.createDirectories(dataDir.resolve("menus"));
        Files.writeString(menus.resolve("island-vault-page.conf"), """
                rows = 3
                content { "skyblock:vault-page" { slots = ["0-26"], editable = true } }
                items { a { slot = 0, material = STONE, click { any = ["close"] } } }
                """);
        SkyblockMenuEngine engine = new SkyblockMenuEngine(
                org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin(), Messages.bundled(), dataDir);
        engine.loadSpecs();
        window.useMenuEngine(engine);
        pageHolds(1, diamonds());

        Inventory top = openPage(1);

        assertThat(top.getHolder()).isInstanceOf(IslandVaultWindow.VaultHolder.class);
        assertThat(top.getItem(0)).isEqualTo(diamonds());
    }

    @Test
    @DisplayName("The page turn writes this page and opens the one it names")
    void thePageTurnOpensTheNamedPage() {
        SkyblockMenuEngine engine = fileEngine();
        pageHolds(1, diamonds());
        pageHolds(2);
        Inventory top = openPage(1);

        ShippedTemplates.click(engine, IslandVaultWindow.TURN, contextOf(top), player, ClickKind.LEFT, "2");

        // Written at the click, not when the next page happens to open: page one takes no click meanwhile.
        verify(vaultService).commitVaultPage(any(), any(), any(), any(), any());
        assertThat(IslandVaultWindow.VaultHolder.of(player.getOpenInventory().getTopInventory())
                        .filter(holder -> holder.page() == 1))
                .isEmpty();
        assertThat(pageOpen(2).getHolder()).isInstanceOf(MenuHolder.class);
        verify(vaultService).openVaultPage(any(), eq(profileId), any(), eq(2), any(), any());
    }

    @Test
    @DisplayName("A page turn that names no page opens nothing")
    void aPageTurnWithNoPageOpensNothing() {
        SkyblockMenuEngine engine = fileEngine();

        ShippedTemplates.click(
                engine, IslandVaultWindow.TURN, MenuContext.of(player, null, 0), player, ClickKind.LEFT, "x");

        verify(vaultService, never()).openVaultPage(any(), any(), any(), anyInt(), any(), any());
    }

    private IslandVaultWindow.VaultHolder holder(boolean mayWithdraw, boolean mayDeposit) {
        List<ItemStack> empty = new ArrayList<>();
        for (int slot = 0; slot < 45; slot++) {
            empty.add(new ItemStack(Material.AIR));
        }
        return new IslandVaultWindow.VaultHolder(
                islandId, 1, profileId, UUID.randomUUID().toString(), empty, mayDeposit, mayWithdraw);
    }

    private SchedulerPort scheduler() {
        SchedulerPort scheduler = mock(SchedulerPort.class);
        doAnswer(call -> {
                    call.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .async(any(Runnable.class));
        doAnswer(call -> {
                    call.getArgument(1, Runnable.class).run();
                    return null;
                })
                .when(scheduler)
                .onEntity(any(PlayerUuid.class), any(Runnable.class));
        doAnswer(call -> null).when(scheduler).asyncAfter(any(Duration.class), any(Runnable.class));
        return scheduler;
    }
}
