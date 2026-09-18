package com.uxplima.uxmskyblock.core.domain.shop;

/**
 * Mathematical model describing the dynamic supply/demand pricing curve,
 * boundaries, elasticity, and damping characteristics for a shop commodity.
 *
 * @param basePrice the canonical baseline price in minor currency units
 * @param floorPrice the hard minimum price boundary in minor currency units
 * @param ceilingPrice the hard maximum price boundary in minor currency units
 * @param elasticity the price sensitivity factor relative to stock deviation
 * @param stockBaseline the reference stock transaction volume unit
 */
public record PricingCurve(long basePrice, long floorPrice, long ceilingPrice, double elasticity, long stockBaseline) {

    public PricingCurve {
        if (basePrice <= 0) {
            throw new IllegalArgumentException("basePrice must be positive: " + basePrice);
        }
        if (floorPrice <= 0 || floorPrice > basePrice) {
            throw new IllegalArgumentException(
                    "floorPrice must be positive and <= basePrice: floor=" + floorPrice + ", base=" + basePrice);
        }
        if (ceilingPrice < basePrice) {
            throw new IllegalArgumentException(
                    "ceilingPrice must be >= basePrice: ceiling=" + ceilingPrice + ", base=" + basePrice);
        }
        if (elasticity <= 0.0) {
            throw new IllegalArgumentException("elasticity must be positive: " + elasticity);
        }
        if (stockBaseline <= 0) {
            throw new IllegalArgumentException("stockBaseline must be positive: " + stockBaseline);
        }
    }

    /**
     * Calculates the raw target price based on current cumulative supply and demand volumes.
     *
     * @param supply total accumulated units sold into the shop
     * @param demand total accumulated units bought from the shop
     * @return bounded price in minor currency units
     */
    public long calculateTargetPrice(long supply, long demand) {
        double deltaVolume = (double) (demand - supply);
        double ratio = deltaVolume / (double) stockBaseline;
        double multiplier = 1.0 + (elasticity * ratio);
        double rawTarget = (double) basePrice * multiplier;
        long rounded = Math.round(rawTarget);

        if (rounded < floorPrice) {
            return floorPrice;
        }
        if (rounded > ceilingPrice) {
            return ceilingPrice;
        }
        return rounded;
    }

    /**
     * Applies a damping factor to smooth price transitions and neutralize market speculation arbitrage.
     *
     * @param previousPrice previous active price in minor units
     * @param targetPrice raw target price in minor units
     * @param dampingFactor smoothing factor in range (0.0, 1.0]
     * @return damped active price
     */
    public static long applyDamping(long previousPrice, long targetPrice, double dampingFactor) {
        if (dampingFactor <= 0.0 || dampingFactor >= 1.0) {
            return targetPrice;
        }
        double delta = (double) (targetPrice - previousPrice);
        return previousPrice + Math.round(delta * dampingFactor);
    }
}
