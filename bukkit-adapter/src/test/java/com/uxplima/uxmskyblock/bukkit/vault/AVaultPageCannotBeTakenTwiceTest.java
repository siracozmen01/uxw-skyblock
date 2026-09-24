package com.uxplima.uxmskyblock.bukkit.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import com.uxplima.uxmskyblock.bukkit.config.VaultConfiguration;
import com.uxplima.uxmskyblock.bukkit.i18n.Messages;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.island.IslandStoragePort;
import com.uxplima.uxmskyblock.core.application.scheduler.SchedulerPort;
import com.uxplima.uxmskyblock.core.application.vault.IslandVaultService;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.identity.ProfileId;
import com.uxplima.uxmskyblock.core.domain.vault.StaleVaultSessionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * A vault page's items cannot be taken out twice.
 *
 * <p>The window's lease was never renewed and the window never closed. A player took a page's
 * diamonds, left the window open past the lease, and closed it onto a refused save: the page kept the
 * diamonds, the player kept them too, and opening the page again handed them over a second time.
 */
class AVaultPageCannotBeTakenTwiceTest extends MockBukkitHarness {

    private IslandVaultService vaultService;
    private IslandVaultWindow window;
    private final AtomicReference<Duration> closeScheduledAfter = new AtomicReference<>();
    private final AtomicReference<Runnable> scheduledClose = new AtomicReference<>();
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        player = createPlayer("Vaulter");
        player.getInventory().clear();
        vaultService = mock(IslandVaultService.class);
        window = new IslandVaultWindow(
                vaultService,
                mock(IslandStoragePort.class),
                scheduler(),
                VaultConfiguration.defaultConfiguration(),
                Messages.bundled(),
                null);
    }

    @Test
    @DisplayName("Diamonds taken out under a refused save come back out of the player's inventory")
    void takenUnderARefusedSaveComesBack() {
        refuseEverySave();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 64));

        window.commit(player, holderOpenedWith(List.of(new ItemStack(Material.DIAMOND, 64))), new ItemStack[] {null});

        assertThat(countOf(Material.DIAMOND))
                .describedAs("the page still holds the sixty four, so the player may not")
                .isZero();
    }

    @Test
    @DisplayName("What the player no longer holds is taken as far as it goes, and nothing more")
    void onlyWhatIsHeldIsTaken() {
        refuseEverySave();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 20));
        player.getInventory().addItem(new ItemStack(Material.EMERALD, 3));

        window.commit(player, holderOpenedWith(List.of(new ItemStack(Material.DIAMOND, 64))), new ItemStack[] {null});

        assertThat(countOf(Material.DIAMOND)).isZero();
        assertThat(countOf(Material.EMERALD)).describedAs("never in the page").isEqualTo(3);
    }

    @Test
    @DisplayName("A refused save that both took and added settles both, each by its own amount")
    void bothDirectionsSettle() {
        refuseEverySave();
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 5));

        window.commit(
                player,
                holderOpenedWith(List.of(new ItemStack(Material.COBBLESTONE, 16), new ItemStack(Material.DIAMOND, 5))),
                new ItemStack[] {new ItemStack(Material.COBBLESTONE, 40), null});

        assertThat(countOf(Material.COBBLESTONE))
                .describedAs("the twenty four added come back")
                .isEqualTo(24);
        assertThat(countOf(Material.DIAMOND))
                .describedAs("the five taken go back to the page")
                .isZero();
    }

    @Test
    @DisplayName("The window closes while its lease still holds, so the save lands")
    void theWindowClosesBeforeTheLease() {
        IslandVaultWindow.VaultHolder holder = holderOpenedWith(List.of());
        Inventory page = Bukkit.createInventory(holder, 27);
        player.openInventory(page);

        window.closeBeforeTheLeaseRunsOut(player, holder);

        assertThat(closeScheduledAfter.get())
                .isLessThan(VaultConfiguration.defaultConfiguration().leaseDuration());
        java.util.Objects.requireNonNull(scheduledClose.get(), "a close was scheduled")
                .run();
        Inventory top = player.getOpenInventory().getTopInventory();
        assertThat(top == null || top.getHolder() != holder)
                .describedAs("the vault window closed")
                .isTrue();
        assertThat(player.nextMessage()).contains("closed so what you changed could be saved");
    }

    @Test
    @DisplayName("A window the player has since swapped for another is left alone")
    void anotherWindowIsLeftAlone() {
        IslandVaultWindow.VaultHolder first = holderOpenedWith(List.of());
        player.openInventory(Bukkit.createInventory(first, 27));
        window.closeBeforeTheLeaseRunsOut(player, first);
        IslandVaultWindow.VaultHolder second = holderOpenedWith(List.of());
        player.openInventory(Bukkit.createInventory(second, 27));

        java.util.Objects.requireNonNull(scheduledClose.get(), "a close was scheduled")
                .run();

        assertThat(player.getOpenInventory().getTopInventory().getHolder()).isSameAs(second);
    }

    private void refuseEverySave() {
        doThrow(new StaleVaultSessionException("the lease expired"))
                .when(vaultService)
                .commitVaultPage(any(), any(), any(), any(), any());
    }

    private static IslandVaultWindow.VaultHolder holderOpenedWith(List<ItemStack> openedWith) {
        return new IslandVaultWindow.VaultHolder(
                IslandId.of(UUID.randomUUID()),
                1,
                new ProfileId(UUID.randomUUID()),
                UUID.randomUUID().toString(),
                openedWith,
                true,
                true);
    }

    private int countOf(Material material) {
        int total = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
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
        doAnswer(call -> {
                    closeScheduledAfter.set(call.getArgument(0, Duration.class));
                    scheduledClose.set(call.getArgument(1, Runnable.class));
                    return null;
                })
                .when(scheduler)
                .asyncAfter(any(Duration.class), any(Runnable.class));
        return scheduler;
    }
}
