package be.kuleuven.pylos.tournament;

import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.Locale;

/**
 * Tracks and calculates head-to-head match statistics between two players using pentanomial game pairs,
 * mirroring fastchess Elo and nElo output metrics.
 */
public class PairStats {

    private final PylosPlayerType p1;
    private final PylosPlayerType p2;

    private int winsP1;
    private int winsP2;
    private int draws;

    private int pentaWW; // P1 wins both
    private int pentaWD; // P1 wins 1, draws 1
    private int pentaWL; // P1 wins 1, loses 1
    private int pentaDD; // Both draw
    private int pentaLD; // P1 loses 1, draws 1
    private int pentaLL; // P1 loses both

    public PairStats(PylosPlayerType p1, PylosPlayerType p2) {
        this.p1 = p1;
        this.p2 = p2;
    }

    /**
     * Records one completed game pair from P1's perspective.
     *
     * @param p1ScoreGame1 score of P1 in game 1 (1.0 = win, 0.5 = draw, 0.0 = loss)
     * @param p1ScoreGame2 score of P1 in game 2 (1.0 = win, 0.5 = draw, 0.0 = loss)
     */
    public synchronized void recordPair(double p1ScoreGame1, double p1ScoreGame2) {
        // Update game win/loss/draw totals
        if (p1ScoreGame1 == 1.0) winsP1++;
        else if (p1ScoreGame1 == 0.5) draws++;
        else winsP2++;

        if (p1ScoreGame2 == 1.0) winsP1++;
        else if (p1ScoreGame2 == 0.5) draws++;
        else winsP2++;

        // Classify pair into pentanomial bins
        double pairScore = p1ScoreGame1 + p1ScoreGame2;
        if (pairScore == 2.0) {
            pentaWW++;
        } else if (pairScore == 1.5) {
            pentaWD++;
        } else if (pairScore == 1.0) {
            if (p1ScoreGame1 == 0.5 && p1ScoreGame2 == 0.5) {
                pentaDD++;
            } else {
                pentaWL++;
            }
        } else if (pairScore == 0.5) {
            pentaLD++;
        } else {
            pentaLL++;
        }
    }

    public PylosPlayerType getP1() {
        return p1;
    }

    public PylosPlayerType getP2() {
        return p2;
    }

    public int getWinsP1() {
        return winsP1;
    }

    public int getWinsP2() {
        return winsP2;
    }

    public int getDraws() {
        return draws;
    }

    public int getTotalPairs() {
        return pentaWW + pentaWD + pentaWL + pentaDD + pentaLD + pentaLL;
    }

    public int getTotalGames() {
        return getTotalPairs() * 2;
    }

    public double getPointsP1() {
        return winsP1 + 0.5 * draws;
    }

    public double getPointsP2() {
        return winsP2 + 0.5 * draws;
    }

    public int getPentaWW() {
        return pentaWW;
    }

    public int getPentaWD() {
        return pentaWD;
    }

    public int getPentaWL() {
        return pentaWL;
    }

    public int getPentaDD() {
        return pentaDD;
    }

    public int getPentaLD() {
        return pentaLD;
    }

    public int getPentaLL() {
        return pentaLL;
    }

    public int getPentaSplit() {
        return pentaWL + pentaDD;
    }

    /**
     * Score of P1 in [0, 1].
     */
    public double getScoreP1() {
        return NormalizedElo.calcScore(pentaWW, pentaWD, pentaWL, pentaDD, pentaLD, pentaLL);
    }

    public double getScoreP2() {
        return 1.0 - getScoreP1();
    }

    /**
     * Empirical variance of the pair distribution (regularized against zero-variance collapse).
     */
    public double getVariance() {
        return NormalizedElo.calcVariance(pentaWW, pentaWD, pentaWL, pentaDD, pentaLD, pentaLL, getScoreP1());
    }

    public double getVariancePerPair() {
        int pairs = getTotalPairs();
        return pairs > 0 ? getVariance() / pairs : 0.0;
    }

    /**
     * Standard logistic Elo difference between P1 and P2 (P1 - P2).
     */
    public double getEloDiff() {
        return NormalizedElo.scoreToEloDiff(getScoreP1());
    }

    /**
     * 95% Confidence Interval error margin for standard Elo difference.
     */
    public double getEloError() {
        int pairs = getTotalPairs();
        if (pairs == 0) return 0.0;
        double sd = Math.sqrt(getVariancePerPair());
        double s = getScoreP1();
        double sUpper = s + NormalizedElo.CI95_Z_SCORE * sd;
        double sLower = s - NormalizedElo.CI95_Z_SCORE * sd;
        return (NormalizedElo.scoreToEloDiff(sUpper) - NormalizedElo.scoreToEloDiff(sLower)) / 2.0;
    }

    /**
     * Normalized Elo (nElo) difference between P1 and P2 (P1 - P2).
     */
    public double getNeloDiff() {
        return NormalizedElo.scoreToNeloDiff(getScoreP1(), getVariance());
    }

    /**
     * 95% Confidence Interval error margin for Normalized Elo difference.
     * In normalized Elo theory, the asymptotic standard error of the t-statistic is 1 / sqrt(N_pairs).
     */
    public double getNeloError() {
        int pairs = getTotalPairs();
        if (pairs == 0) return 0.0;
        return (NormalizedElo.CI95_Z_SCORE * NormalizedElo.CONSTANT_800_LN10) / Math.sqrt(2.0 * pairs);
    }

    /**
     * Likelihood of Superiority (LOS) of P1 over P2 (in %).
     */
    public double getLos() {
        return NormalizedElo.calcLos(getScoreP1(), getVariancePerPair());
    }

    /**
     * Percentage of games that ended in a draw.
     */
    public double getDrawRatio() {
        int total = getTotalGames();
        return total > 0 ? ((double) draws / total) * 100.0 : 0.0;
    }

    /**
     * Formats this matchup into the fastchess summary report style (W - L - D).
     */
    public String formatSummary() {
        int games = getTotalGames();
        double score = getScoreP1();
        double elo = getEloDiff();
        double eloErr = getEloError();
        double nelo = getNeloDiff();
        double neloErr = getNeloError();
        double los = getLos();
        double drawRate = getDrawRatio();

        return String.format(Locale.US,
                "Score of %s vs %s: %d - %d - %d [%.3f] %d\n" +
                "Elo difference: %+7.2f +/- %6.2f, LOS: %5.2f%%, DrawRatio: %5.2f%%\n" +
                "nElo difference: %+7.2f +/- %6.2f\n" +
                "Ptnml(0-2): [%d, %d, %d, %d, %d]",
                p1, p2, winsP1, winsP2, draws, score, games,
                elo, eloErr, los, drawRate,
                nelo, neloErr,
                pentaLL, pentaLD, (pentaWL + pentaDD), pentaWD, pentaWW
        );
    }

    @Override
    public String toString() {
        return formatSummary();
    }
}
