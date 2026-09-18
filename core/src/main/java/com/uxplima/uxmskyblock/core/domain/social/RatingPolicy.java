package com.uxplima.uxmskyblock.core.domain.social;

/**
 * Encapsulates rating validation bounds and Bayesian scoring formulas.
 */
public interface RatingPolicy {

    int minScore();

    int maxScore();

    default void validateScore(int score) {
        if (score < minScore() || score > maxScore()) {
            throw new IllegalArgumentException(
                    "Rating score " + score + " is out of bounds [" + minScore() + ", " + maxScore() + "]");
        }
    }

    /**
     * Calculates a Bayesian weighted score:
     * \(\frac{C \cdot m + \sum x}{C + n}\)
     * where \(C\) is prior weight, \(m\) is prior mean, \(n\) is count, and \(\sum x\) is sum of ratings.
     */
    default double calculateBayesianScore(int count, double sum, int priorWeight, double priorMean) {
        if (count == 0) {
            return priorMean;
        }
        return (priorWeight * priorMean + sum) / (priorWeight + count);
    }

    static RatingPolicy standardFiveStar() {
        return new RatingPolicy() {
            @Override
            public int minScore() {
                return 1;
            }

            @Override
            public int maxScore() {
                return 5;
            }
        };
    }
}
