package com.uxplima.uxmskyblock.bukkit.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.uxplima.uxmskyblock.core.application.shop.DynamicPricingEngine;
import com.uxplima.uxmskyblock.core.domain.shop.PricingCurve;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

/**
 * The shop trades what the operator's file says, and nothing else.
 *
 * <p>The pricing engine was built, tuned and registered as a service, and nothing ever put an item
 * in it. It had a damping factor, an elasticity and a stock baseline for commodities that did not
 * exist, so every price it could be asked for was absent and every method that moves a price had no
 * caller. The architecture names dynamic shop pricing as a version one requirement.
 */
class TheShopSellsWhatTheFileSaysTest {

    private static CommentedConfigurationNode parse(String hocon) throws Exception {
        return HoconConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(hocon)))
                .build()
                .load();
    }

    @Test
    @DisplayName("An item an operator adds is an item the shop prices")
    void anOperatorCanAddOne() throws Exception {
        ShopConfiguration config = ShopConfiguration.load(parse("""
                shop {
                    items {
                        diamond {
                            base-price = 20000
                            floor-price = 8000
                            ceiling-price = 60000
                            elasticity = 0.7
                            stock-baseline = 200
                        }
                    }
                }
                """));

        assertThat(config.items()).containsKey("DIAMOND");
        PricingCurve curve = java.util.Objects.requireNonNull(config.items().get("DIAMOND"));
        assertThat(curve.basePrice()).isEqualTo(20000L);
        assertThat(curve.floorPrice()).isEqualTo(8000L);
        assertThat(curve.ceilingPrice()).isEqualTo(60000L);
        assertThat(curve.elasticity()).isEqualTo(0.7);
        assertThat(curve.stockBaseline()).isEqualTo(200L);
    }

    @Test
    @DisplayName("An item that names only a price gets a floor, a ceiling and the shop's own settings")
    void onlyThePriceIsRequired() throws Exception {
        ShopConfiguration config = ShopConfiguration.load(parse("""
                shop {
                    default-elasticity = 0.25
                    default-stock-baseline = 777
                    items {
                        wheat { base-price = 120 }
                    }
                }
                """));

        PricingCurve curve = java.util.Objects.requireNonNull(config.items().get("WHEAT"));
        assertThat(curve.basePrice()).isEqualTo(120L);
        assertThat(curve.floorPrice()).describedAs("half the base price").isEqualTo(60L);
        assertThat(curve.ceilingPrice()).describedAs("double the base price").isEqualTo(240L);
        assertThat(curve.elasticity()).isEqualTo(0.25);
        assertThat(curve.stockBaseline()).isEqualTo(777L);
    }

    @Test
    @DisplayName("One item whose numbers make no curve does not stop the shop selling the rest")
    void oneBadBlockDoesNotCloseTheShop() throws Exception {
        ShopConfiguration config = ShopConfiguration.load(parse("""
                shop {
                    items {
                        stone { base-price = 150 }
                        nonsense { base-price = 100, floor-price = 500 }
                        nothing { elasticity = 0.5 }
                    }
                }
                """));

        assertThat(config.items()).containsOnlyKeys("STONE");
    }

    @Test
    @DisplayName("A file that names no item leaves a shop that trades nothing, rather than failing to load")
    void noItemsIsAShopThatTradesNothing() throws Exception {
        assertThat(ShopConfiguration.load(parse("shop { enabled = true }")).items())
                .isEmpty();
    }

    @Test
    @DisplayName("Every item the shipped file names is one the engine can price")
    void theShippedFileReallyLoads() throws Exception {
        String shipped = Files.readString(Path.of("src/main/resources/modules/shop.conf"), StandardCharsets.UTF_8);
        ShopConfiguration config = ShopConfiguration.load(parse(shipped));

        assertThat(config.items())
                .describedAs("the commodities the plugin ships with")
                .hasSizeGreaterThan(5);

        DynamicPricingEngine engine = new DynamicPricingEngine(config.dampingFactor());
        config.items().forEach(engine::registerItem);

        for (String key : config.items().keySet()) {
            assertThat(engine.hasItem(key))
                    .describedAs("%s is in the engine", key)
                    .isTrue();
            assertThat(engine.getUnitPrice(key).isPresent())
                    .describedAs("%s has a price", key)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Every material the shipped file names is a material this server knows")
    void everyShippedMaterialIsReal() throws Exception {
        String shipped = Files.readString(Path.of("src/main/resources/modules/shop.conf"), StandardCharsets.UTF_8);

        for (String key : ShopConfiguration.load(parse(shipped)).items().keySet()) {
            assertThat(org.bukkit.Material.matchMaterial(key))
                    .describedAs("an item nobody can hold cannot be sold: %s", key)
                    .isNotNull();
        }
    }
}
