package com.uxplima.uxmskyblock.core.application.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import com.uxplima.uxmskyblock.core.application.bank.IslandBankService;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransaction;
import com.uxplima.uxmskyblock.core.domain.bank.BankTransactionOutcome;
import com.uxplima.uxmskyblock.core.domain.bank.IslandBank;
import com.uxplima.uxmskyblock.core.domain.identity.IslandId;
import com.uxplima.uxmskyblock.core.domain.identity.PlayerUuid;
import com.uxplima.uxmskyblock.core.domain.session.ServerNodeId;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shop price moves because somebody traded, and for no other reason.
 *
 * <p>The pricing engine could raise and lower a price and nothing ever asked it to: the two methods
 * that move one had no caller anywhere, because there was nothing to be paid by and nothing to pay.
 *
 * <p>What matters most here is the order. The money moves first. A trade the bank refuses must leave
 * the price exactly where it was, because a price that moved for a trade that did not happen is a
 * price every other player pays for nothing.
 */
class APriceOnlyMovesBecauseSomebodyTradedTest {

    private static final IslandId ISLAND = IslandId.of(UUID.randomUUID());
    private static final PlayerUuid ACTOR = new PlayerUuid(UUID.randomUUID());
    private static final ServerNodeId NODE = ServerNodeId.of("node-1");
    private static final String DIAMOND = "DIAMOND";

    private DynamicPricingEngine engine;
    private IslandBankService bankService;
    private IslandShopService shop;

    @BeforeEach
    void setUp() {
        engine = new DynamicPricingEngine(1.0);
        engine.registerItem(DIAMOND, new PricingCurve(20_000L, 8_000L, 60_000L, 0.7, 200L));
        bankService = mock(IslandBankService.class);
        shop = new IslandShopService(engine, bankService);
    }

    private void bankAccepts(long balanceAfter) {
        IslandBank bank = new IslandBank(ISLAND, balanceAfter, 0L, 0L, 2L, Instant.now());
        BankTransaction tx = new BankTransaction(
                UUID.randomUUID(),
                UUID.randomUUID(),
                ISLAND,
                ACTOR.value(),
                "PRIMARY",
                2,
                0L,
                balanceAfter,
                "shop",
                Instant.now());
        when(bankService.withdrawFromIsland(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));
        when(bankService.depositToIsland(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(new BankTransactionOutcome.Success(bank, tx));
    }

    @Test
    @DisplayName("A purchase the bank paid for moves the price")
    void aPaidPurchaseMovesThePrice() {
        bankAccepts(500_000L);
        long before = engine.getUnitPrice(DIAMOND).orElseThrow();

        IslandShopService.TradeResult result = shop.buy(ISLAND, ACTOR, DIAMOND, 100L, NODE);

        assertThat(result).isInstanceOf(IslandShopService.TradeResult.Traded.class);
        assertThat(engine.getUnitPrice(DIAMOND).orElseThrow())
                .describedAs("buying pushes the price up")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("A purchase the bank refused leaves the price exactly where it was")
    void arefusedPurchaseLeavesThePriceAlone() {
        when(bankService.withdrawFromIsland(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(new BankTransactionOutcome.InsufficientFunds(10L, -2_000_000L));
        long before = engine.getUnitPrice(DIAMOND).orElseThrow();

        IslandShopService.TradeResult result = shop.buy(ISLAND, ACTOR, DIAMOND, 100L, NODE);

        assertThat(result).isInstanceOf(IslandShopService.TradeResult.CannotAfford.class);
        assertThat(engine.getUnitPrice(DIAMOND).orElseThrow())
                .describedAs("a price that moved for a trade that did not happen")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("A sale the bank refused leaves the price exactly where it was")
    void aRefusedSaleLeavesThePriceAlone() {
        when(bankService.depositToIsland(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(new BankTransactionOutcome.AuthorityRejected("another node holds this island"));
        long before = engine.getUnitPrice(DIAMOND).orElseThrow();

        IslandShopService.TradeResult result = shop.sell(ISLAND, ACTOR, DIAMOND, 100L, NODE);

        assertThat(result).isInstanceOf(IslandShopService.TradeResult.Refused.class);
        assertThat(engine.getUnitPrice(DIAMOND).orElseThrow()).isEqualTo(before);
    }

    @Test
    @DisplayName("A sale the bank paid for moves the price the other way")
    void aPaidSaleMovesThePriceDown() {
        bankAccepts(700_000L);
        long before = engine.getUnitPrice(DIAMOND).orElseThrow();

        shop.sell(ISLAND, ACTOR, DIAMOND, 100L, NODE);

        assertThat(engine.getUnitPrice(DIAMOND).orElseThrow())
                .describedAs("selling pushes the price down")
                .isLessThan(before);
    }

    @Test
    @DisplayName("The shop does not trade what the operator did not name")
    void anUnknownItemIsNotTraded() {
        IslandShopService.TradeResult result = shop.buy(ISLAND, ACTOR, "BEDROCK", 1L, NODE);

        assertThat(result).isInstanceOf(IslandShopService.TradeResult.UnknownItem.class);
    }

    @Test
    @DisplayName("An item named in any case is the same item")
    void theNameIsReadInAnyCase() {
        bankAccepts(500_000L);

        assertThat(shop.buy(ISLAND, ACTOR, "diamond", 1L, NODE))
                .isInstanceOf(IslandShopService.TradeResult.Traded.class);
        assertThat(shop.unitPrice("  DiAmOnD ")).isPresent();
    }

    @Test
    @DisplayName("An amount no bank could hold is refused rather than wrapping round")
    void anAmountThatOverflowsIsRefused() {
        IslandShopService.TradeResult result = shop.buy(ISLAND, ACTOR, DIAMOND, Long.MAX_VALUE, NODE);

        assertThat(result).isInstanceOf(IslandShopService.TradeResult.Refused.class);
    }

    @Test
    @DisplayName("The total charged is the unit price times the amount")
    void theTotalIsWhatItSays() {
        bankAccepts(500_000L);
        long unit = engine.getUnitPrice(DIAMOND).orElseThrow();

        IslandShopService.TradeResult result = shop.buy(ISLAND, ACTOR, DIAMOND, 7L, NODE);

        IslandShopService.TradeResult.Traded traded = (IslandShopService.TradeResult.Traded) result;
        assertThat(traded.unitPrice()).isEqualTo(unit);
        assertThat(traded.quantity()).isEqualTo(7L);
        assertThat(traded.total()).isEqualTo(unit * 7L);
    }

    @Test
    @DisplayName("The catalogue is every commodity the operator named")
    void theCatalogueIsTheOperatorsList() {
        engine.registerItem("STONE", new PricingCurve(150L, 75L, 300L, 0.3, 5_000L));

        assertThat(shop.catalogue()).extracting(price -> price.itemKey()).containsExactly(DIAMOND, "STONE");
    }

    @Test
    @DisplayName("A refused trade carries the bank's own answer, so a player can be told it in words")
    void aRefusedTradeCarriesTheBanksAnswer() {
        BankTransactionOutcome held = new BankTransactionOutcome.AuthorityRejected(
                BankTransactionOutcome.AuthorityRejected.Kind.NO_AUTHORITY, "held by node-7");
        when(bankService.depositToIsland(any(), any(), anyLong(), anyString(), any()))
                .thenReturn(held);

        IslandShopService.TradeResult result = shop.sell(ISLAND, ACTOR, DIAMOND, 100L, NODE);

        assertThat(result)
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(
                        IslandShopService.TradeResult.Refused.class))
                .extracting(IslandShopService.TradeResult.Refused::bank)
                .isEqualTo(held);
    }
}
