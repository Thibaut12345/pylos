package be.kuleuven.pylos.tournament;

import be.kuleuven.pylos.player.PylosPlayerType;

/**
 * Represents the final or intermediate tournament standing of a single player,
 * including overall score, win/draw rates, aggregated pentanomial distribution,
 * and tournament Elo / Normalized Elo (nElo) ratings with confidence error margins.
 */
public class PlayerStanding implements Comparable<PlayerStanding> {

    private final int rank;
    private final PylosPlayerType player;
    private final int totalGames;
    private final int totalPairs;
    private final int wins;
    private final int draws;
    private final int losses;
    private final double points;
    private final double score;
    private final double winRate;
    private final double drawRate;

    private final int pentaWW;
    private final int pentaWD;
    private final int pentaWL;
    private final int pentaDD;
    private final int pentaLD;
    private final int pentaLL;

    private final double elo;
    private final double eloError;
    private final double nelo;
    private final double neloError;

    public PlayerStanding(int rank, PylosPlayerType player, int totalGames, int totalPairs,
                          int wins, int draws, int losses, double points,
                          int pentaWW, int pentaWD, int pentaWL, int pentaDD, int pentaLD, int pentaLL,
                          double elo, double eloError, double nelo, double neloError) {
        this.rank = rank;
        this.player = player;
        this.totalGames = totalGames;
        this.totalPairs = totalPairs;
        this.wins = wins;
        this.draws = draws;
        this.losses = losses;
        this.points = points;
        this.score = totalGames > 0 ? points / totalGames : 0.5;
        this.winRate = totalGames > 0 ? ((double) wins / totalGames) * 100.0 : 0.0;
        this.drawRate = totalGames > 0 ? ((double) draws / totalGames) * 100.0 : 0.0;
        this.pentaWW = pentaWW;
        this.pentaWD = pentaWD;
        this.pentaWL = pentaWL;
        this.pentaDD = pentaDD;
        this.pentaLD = pentaLD;
        this.pentaLL = pentaLL;
        this.elo = elo;
        this.eloError = eloError;
        this.nelo = nelo;
        this.neloError = neloError;
    }

    public int getRank() {
        return rank;
    }

    public PylosPlayerType getPlayer() {
        return player;
    }

    public int getTotalGames() {
        return totalGames;
    }

    public int getTotalPairs() {
        return totalPairs;
    }

    public int getWins() {
        return wins;
    }

    public int getDraws() {
        return draws;
    }

    public int getLosses() {
        return losses;
    }

    public double getPoints() {
        return points;
    }

    public double getScore() {
        return score;
    }

    public double getWinRate() {
        return winRate;
    }

    public double getDrawRate() {
        return drawRate;
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

    public double getElo() {
        return elo;
    }

    public double getEloError() {
        return eloError;
    }

    public double getNelo() {
        return nelo;
    }

    public double getNeloError() {
        return neloError;
    }

    @Override
    public int compareTo(PlayerStanding other) {
        // Higher score/points comes first
        int cmp = Double.compare(other.points, this.points);
        if (cmp != 0) return cmp;
        return Double.compare(other.nelo, this.nelo);
    }
}
