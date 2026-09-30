package be.kuleuven.pylos.tournament;

import java.util.Arrays;

/**
 * Mathematical utilities and statistical functions for Elo and Normalized Elo (nElo) calculations,
 * mirroring fastchess and Michel Van den Bergh's normalized Elo framework for pentanomial/trinomial distributions.
 */
public final class NormalizedElo {

    /**
     * 95% Confidence Interval critical two-tailed Z-score (~1.96).
     */
    public static final double CI95_Z_SCORE = 1.959963984540054;

    /**
     * Constant factor 800 / ln(10) used to convert between logistic units and normalized Elo.
     * Approximately 347.43558552260146.
     */
    public static final double CONSTANT_800_LN10 = 800.0 / Math.log(10.0);

    /**
     * Clamping bounds for score in logistic conversion to avoid division by zero while
     * allowing very large skill differences (1e-4 corresponds to +/- 1600 Elo).
     */
    private static final double MIN_SCORE = 1e-4;
    private static final double MAX_SCORE = 1.0 - 1e-4;

    /**
     * Minimum variance floor (~90% draw rate baseline) to regularize degenerate zero-variance cases in small samples.
     */
    private static final double MIN_VARIANCE = 0.015;

    private NormalizedElo() {
    }

    /**
     * Converts a score (fraction in [0, 1]) to a standard logistic Elo difference.
     * Formula: -400 * log10(1 / score - 1)
     */
    public static double scoreToEloDiff(double score) {
        double clamped = Math.clamp(score, MIN_SCORE, MAX_SCORE);
        return -400.0 * Math.log10(1.0 / clamped - 1.0);
    }

    /**
     * Converts a logistic Elo difference back into expected score.
     * Formula: 1 / (1 + 10^(-elo / 400))
     */
    public static double eloDiffToScore(double eloDiff) {
        return 1.0 / (1.0 + Math.pow(10.0, -eloDiff / 400.0));
    }

    /**
     * Converts score and variance into Normalized Elo (nElo) difference for a pentanomial pair distribution.
     * Formula: (score - 0.5) / sqrt(2 * variance) * (800 / ln(10))
     */
    public static double scoreToNeloDiff(double score, double variance) {
        double safeVar = Math.max(MIN_VARIANCE, variance);
        return (score - 0.5) / Math.sqrt(2.0 * safeVar) * CONSTANT_800_LN10;
    }

    /**
     * Converts Normalized Elo (nElo) back into score for a pentanomial pair distribution.
     * Formula: nelo * sqrt(2 * variance) / (800 / ln(10)) + 0.5
     */
    public static double neloToScore(double nelo, double variance) {
        double safeVar = Math.max(MIN_VARIANCE, variance);
        return nelo * Math.sqrt(2.0 * safeVar) / CONSTANT_800_LN10 + 0.5;
    }

    /**
     * Computes pair score from pentanomial counts (WW, WD, WL, DD, LD, LL).
     * Score weights per pair:
     * WW: 1.00
     * WD: 0.75
     * WL, DD: 0.50
     * LD: 0.25
     * LL: 0.00
     */
    public static double calcScore(int ww, int wd, int wl, int dd, int ld, int ll) {
        int totalPairs = ww + wd + wl + dd + ld + ll;
        if (totalPairs == 0) return 0.5;
        return (ww + 0.75 * wd + 0.5 * (wl + dd) + 0.25 * ld) / totalPairs;
    }

    /**
     * Computes variance per pair for pentanomial outcomes against a given score.
     * Uses a symmetric Dirichlet prior (+0.5 per pentanomial bin) to prevent variance
     * from collapsing to 0 when all observations in a small sample fall into a single bin.
     */
    public static double calcVariance(int ww, int wd, int wl, int dd, int ld, int ll, double score) {
        int totalPairs = ww + wd + wl + dd + ld + ll;
        if (totalPairs == 0) return 0.125;

        // Symmetric Dirichlet prior (pseudo-count 0.5 across the 5 pentanomial bins)
        double k = 0.5;
        double smoothedTotal = totalPairs + 5.0 * k;

        double pWW = (ww + k) / smoothedTotal;
        double pWD = (wd + k) / smoothedTotal;
        double pSplit = (wl + dd + k) / smoothedTotal;
        double pLD = (ld + k) / smoothedTotal;
        double pLL = (ll + k) / smoothedTotal;

        double dWW = 1.00 - score;
        double dWD = 0.75 - score;
        double dSplit = 0.50 - score;
        double dLD = 0.25 - score;
        double dLL = 0.00 - score;

        double var = pWW * dWW * dWW
                   + pWD * dWD * dWD
                   + pSplit * dSplit * dSplit
                   + pLD * dLD * dLD
                   + pLL * dLL * dLL;

        return Math.max(MIN_VARIANCE, var);
    }

    /**
     * Computes the Likelihood of Superiority (LOS) in percentage [0, 100].
     * Represents the probability that Player 1 is superior to Player 2.
     * Formula: Phi((score - 0.5) / sqrt(variance_per_pair)) * 100%
     */
    public static double calcLos(double score, double variancePerPair) {
        if (variancePerPair <= 1e-12) {
            if (score > 0.5) return 100.0;
            if (score < 0.5) return 0.0;
            return 50.0;
        }
        double z = (score - 0.5) / Math.sqrt(variancePerPair);
        return Math.clamp(phi(z) * 100.0, 0.0, 100.0);
    }

    /**
     * High-precision Gauss error function erf(x) via Abramowitz & Stegun formula 7.1.26.
     * Maximum error < 1.5e-7.
     */
    public static double erf(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x == 0.0) return 0.0;
        boolean negative = x < 0.0;
        double absX = Math.abs(x);
        if (absX > 6.0) return negative ? -1.0 : 1.0;

        double p = 0.3275911;
        double a1 = 0.254829592;
        double a2 = -0.284496736;
        double a3 = 1.421413741;
        double a4 = -1.453152027;
        double a5 = 1.061405429;

        double t = 1.0 / (1.0 + p * absX);
        double poly = (((a5 * t + a4) * t + a3) * t + a2) * t + a1;
        double y = 1.0 - poly * t * Math.exp(-absX * absX);
        return negative ? -y : y;
    }

    /**
     * Standard cumulative normal distribution function Phi(z).
     */
    public static double phi(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /**
     * Solves Bradley-Terry maximum likelihood ratings for all players in a tournament matrix.
     * Uses a balanced Bayesian prior (+0.5 wins, +1.0 games per played matchup) to regularize
     * zero/undefeated records, zero-centering the results (mean = 0.0).
     */
    public static double[] solveBradleyTerryElo(int numPlayers, double[][] pointMatrix, int[][] gameMatrix) {
        if (numPlayers <= 1) return new double[numPlayers];

        double[] gamma = new double[numPlayers];
        Arrays.fill(gamma, 1.0);

        double[] wins = new double[numPlayers];
        for (int i = 0; i < numPlayers; i++) {
            double w = 0.0;
            for (int j = 0; j < numPlayers; j++) {
                if (i != j && gameMatrix[i][j] > 0) {
                    w += pointMatrix[i][j] + 0.5; // Balanced paired prior
                }
            }
            wins[i] = w;
        }

        for (int iter = 0; iter < 100; iter++) {
            double[] nextGamma = new double[numPlayers];
            for (int i = 0; i < numPlayers; i++) {
                double denominator = 0.0;
                for (int j = 0; j < numPlayers; j++) {
                    if (i != j && gameMatrix[i][j] > 0) {
                        double totalGames = gameMatrix[i][j] + 1.0;
                        denominator += totalGames / (gamma[i] + gamma[j]);
                    }
                }
                nextGamma[i] = denominator > 0 ? wins[i] / denominator : gamma[i];
            }

            double sum = 0.0;
            for (double g : nextGamma) sum += g;
            for (int i = 0; i < numPlayers; i++) {
                gamma[i] = (nextGamma[i] / sum) * numPlayers;
            }
        }

        double[] elo = new double[numPlayers];
        double mean = 0.0;
        for (int i = 0; i < numPlayers; i++) {
            elo[i] = 400.0 * Math.log10(gamma[i]);
            mean += elo[i];
        }
        mean /= numPlayers;
        for (int i = 0; i < numPlayers; i++) {
            elo[i] -= mean;
        }

        return elo;
    }
}
