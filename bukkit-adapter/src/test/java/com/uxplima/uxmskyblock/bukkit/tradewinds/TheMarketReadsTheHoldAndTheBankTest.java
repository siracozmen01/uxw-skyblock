package com.uxplima.uxmskyblock.bukkit.tradewinds;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import net.kyori.adventure.text.Component;

import com.uxplima.uxmskyblock.bukkit.config.TradeWindsConfiguration;
import com.uxplima.uxmskyblock.bukkit.inventory.BukkitInventorySerializer;
import com.uxplima.uxmskyblock.bukkit.test.MockBukkitHarness;
import com.uxplima.uxmskyblock.core.application.tradewinds.PortMarket.Bank.Answer;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * A port counts only the plain goods of a hold, and every answer of the island bank is read for whether
 * the key it was asked under is spent.
 */
class TheMarketReadsTheHoldAndTheBankTest extends MockBukkitHarness {

    @Test
    @DisplayName("Only plain goods are counted, taken and stowed, and a lot that does not fit is not stowed")
    void plainGoodsOnly() {
        ItemStack named = new ItemStack(Material.WHEAT, 10);
        ItemMeta meta = named.getItemMeta();
        meta.displayName(Component.text("Grandma's wheat"));
        named.setItemMeta(meta);
        byte[] hold = BukkitInventorySerializer.serializeItemStacks(
                new ItemStack[] {new ItemStack(Material.WHEAT, 40), named, new ItemStack(Material.WHEAT, 30)});
        HoldGoods goods = new HoldGoods();

        assertThat(goods.count(hold, "WHEAT")).isEqualTo(70);
        assertThat(goods.take(hold, "WHEAT", 71)).isEmpty();
        byte[] taken = goods.take(hold, "WHEAT", 50).orElseThrow();
        assertThat(goods.count(taken, "WHEAT")).isEqualTo(20);
        assertThat(BukkitInventorySerializer.deserializeItemStacks(taken)[1]
                        .getItemMeta()
                        .displayName())
                .describedAs("the crew's own wheat stays theirs")
                .isNotNull();
        assertThat(goods.stow(taken, 3, "WHEAT", 44 + 64)).isPresent();
        assertThat(goods.stow(taken, 3, "WHEAT", 44 + 64 + 1)).isEmpty();
        assertThat(goods.count(hold, "NOT_AN_ITEM")).isZero();
        assertThat(goods.stow(new byte[0], 3, "WHEAT", 64 * 3)).isPresent();
        assertThat(goods.stow(new byte[0], 3, "WHEAT", 64 * 3 + 1)).isEmpty();
    }

    @Test
    @DisplayName("Only an answer the bank wrote down as refused spends a key")
    void theBankIsRead() {
        UUID op = UUID.randomUUID();
        assertThat(IslandBankMarket.answer(new BankTransactionOutcome.InsufficientFunds(5, -10)))
                .isEqualTo(Answer.NO_FUNDS);
        assertThat(IslandBankMarket.answer(new BankTransactionOutcome.StaleVersion(1, 2)))
                .isEqualTo(Answer.REFUSED);
        assertThat(IslandBankMarket.answer(
                        new BankTransactionOutcome.DuplicateOperation(op, "", "APPLIED", null, null)))
                .isEqualTo(Answer.LANDED);
        assertThat(IslandBankMarket.answer(
                        new BankTransactionOutcome.DuplicateOperation(op, "", "REJECTED", "INSUFFICIENT_FUNDS", null)))
                .isEqualTo(Answer.NO_FUNDS);
        assertThat(IslandBankMarket.answer(
                        new BankTransactionOutcome.DuplicateOperation(op, "", "REJECTED", "STALE_OCC_VERSION", null)))
                .isEqualTo(Answer.REFUSED);
        assertThat(IslandBankMarket.answer(
                        new BankTransactionOutcome.DuplicateOperation(op, "", "PENDING", null, null)))
                .isEqualTo(Answer.UNKNOWN);
        assertThat(IslandBankMarket.answer(new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY, "elsewhere")))
                .describedAs("refused before the key was written")
                .isEqualTo(Answer.NOT_NOW);
        assertThat(IslandBankMarket.answer(new BankTransactionOutcome.AuthorityRejected(
                        BankTransactionOutcome.AuthorityRejected.Kind.OUTCOME_UNKNOWN, "no answer")))
                .isEqualTo(Answer.UNKNOWN);
    }

    @Test
    @DisplayName("Ports are read in the order written, and a port or a good that cannot be is left out")
    void portsAreRead() throws Exception {
        TradeWindsConfiguration read =
                TradeWindsConfiguration.load(HoconConfigurationLoader.builder().buildAndLoadString("""
                        ports = [
                            { id = "b", voyage-seconds = 5, market = [
                                { item = "wheat", lot = 2, pays = 1, asks = 3 }
                                { item = "NOT_AN_ITEM", pays = 1 }
                                { item = "STONE", pays = 5, asks = 5 }
                            ] }
                            { id = "a", icon = "NOT_AN_ITEM" }
                            { id = "Bad Id" }
                            { id = "a" }
                            { id = "c", voyage-seconds = -1 }
                        ]
                        """));

        assertThat(read.ports()).extracting(port -> port.id()).containsExactly("b", "a");
        assertThat(read.ports().get(0).goods()).hasSize(1);
        assertThat(read.ports().get(0).goods().get(0).item()).isEqualTo("WHEAT");
        assertThat(read.icon(read.ports().get(1))).isEqualTo("OAK_BOAT");
        assertThat(read.standing()).isEqualTo(TradeWindsConfiguration.SHIPPED_STANDING);
        assertThat(TradeWindsConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString("ports = []"))
                        .ports())
                .isEmpty();
        assertThat(TradeWindsConfiguration.load(
                                HoconConfigurationLoader.builder().buildAndLoadString(""))
                        .ports())
                .isEqualTo(TradeWindsConfiguration.SHIPPED_PORTS);
        assertThat(TradeWindsConfiguration.load(HoconConfigurationLoader.builder()
                                .buildAndLoadString("standing { percent = 30, max-steps = 10 }"))
                        .standing())
                .isEqualTo(TradeWindsConfiguration.SHIPPED_STANDING);
    }
}
