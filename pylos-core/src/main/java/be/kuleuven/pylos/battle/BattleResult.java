package be.kuleuven.pylos.battle;

import be.kuleuven.pylos.battle.data.PlayedGame;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

public class BattleResult {
    public PylosPlayerType p1;
    public PylosPlayerType p2;
    public long runTime;
    public int p1StartP1Wins;
    public int p1StartDraws;
    public int p1StartP2Wins;
    public int p2StartP1Wins;
    public int p2StartDraws;
    public int p2StartP2Wins;

    public ArrayList<PlayedGame> playedGames;

    public BattleResult(PylosPlayerType p1, PylosPlayerType p2, long runTime, int p1StartP1Wins, int p1StartDraws, int p1StartP2Wins, int p2StartP1Wins, int p2StartDraws, int p2StartP2Wins, ArrayList<PlayedGame> playedGames) {
        this.p1 = p1;
        this.p2 = p2;
        this.runTime = runTime;
        this.p1StartP1Wins = p1StartP1Wins;
        this.p1StartDraws = p1StartDraws;
        this.p1StartP2Wins = p1StartP2Wins;
        this.p2StartP1Wins = p2StartP1Wins;
        this.p2StartDraws = p2StartDraws;
        this.p2StartP2Wins = p2StartP2Wins;
        this.playedGames = playedGames;
    }

    public static BattleResult merge(Collection<BattleResult> brs) {
        //assert all results are of the same players
        PylosPlayerType p1 = brs.iterator().next().p1;
        PylosPlayerType p2 = brs.iterator().next().p2;

        if (brs.stream().anyMatch(br -> br.p1 != p1 || br.p2 != p2)) {
            throw new IllegalArgumentException("All BattleResults should be of the same players");
        }

        long runTime = brs.stream().mapToLong(br -> br.runTime).sum();
        int p1StartP1Wins = brs.stream().mapToInt(br -> br.p1StartP1Wins).sum();
        int p1StartDraws = brs.stream().mapToInt(br -> br.p1StartDraws).sum();
        int p1StartP2Wins = brs.stream().mapToInt(br -> br.p1StartP2Wins).sum();
        int p2StartP1Wins = brs.stream().mapToInt(br -> br.p2StartP1Wins).sum();
        int p2StartDraws = brs.stream().mapToInt(br -> br.p2StartDraws).sum();
        int p2StartP2Wins = brs.stream().mapToInt(br -> br.p2StartP2Wins).sum();

        ArrayList<PlayedGame> playedGames = brs.stream().flatMap(br -> br.playedGames.stream()).collect(Collectors.toCollection(ArrayList::new));

        return new BattleResult(p1, p2, runTime, p1StartP1Wins, p1StartDraws, p1StartP2Wins, p2StartP1Wins, p2StartDraws, p2StartP2Wins, playedGames);
    }

    public int p1Wins() {
        return p1StartP1Wins + p2StartP1Wins;
    }

    public int p2Wins() {
        return p1StartP2Wins + p2StartP2Wins;
    }

    public void print() {
        int nGamesP1Start = p1StartP1Wins + p1StartDraws + p1StartP2Wins;
        int nGamesP2Start = p2StartP1Wins + p2StartDraws + p2StartP2Wins;
        int nGames = nGamesP1Start + nGamesP2Start;

        System.out.println();
        System.out.println("----------------------------");
        System.out.println(nGamesP1Start + " games where " + p1 + " starts:");
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) p1StartP1Wins / nGamesP1Start * 100)) + "% " + p1);
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) p1StartP2Wins / nGamesP1Start * 100)) + "% " + p2);
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) p1StartDraws / nGamesP1Start * 100)) + "% Draw");
        System.out.println();
        System.out.println(nGamesP2Start + " games where " + p2 + " starts:");
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) p2StartP1Wins / nGamesP2Start * 100)) + "% " + p1);
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) p2StartP2Wins / nGamesP2Start * 100)) + "% " + p2);
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) p2StartDraws / nGamesP2Start * 100)) + "% Draw");
        System.out.println();
        System.out.println(nGames + " games in total:");
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) (p1StartP1Wins + p2StartP1Wins) / nGames * 100)) + "% " + p1);
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) (p1StartP2Wins + p2StartP2Wins) / nGames * 100)) + "% " + p2);
        System.out.println(String.format(" * %6s", String.format("%.2f", (double) (p1StartDraws + p2StartDraws) / nGames * 100)) + "% Draw");
        System.out.println();
        System.out.println("CPU Time: " + String.format("%.2f", (double) runTime / 1000) + " sec (" + String.format("%.2f", (double) runTime / 1000 / nGames) + " sec / game)");
        System.out.println("----------------------------");
    }
}
