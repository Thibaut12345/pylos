package be.kuleuven.pylos.battle;

import be.kuleuven.pylos.player.PylosPlayerFactory;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.*;

public class RoundRobin {

    public static void play(Collection<Match> matches, int nRuns, int nThreads) {
        System.out.println("Starting round robin tournament: " + matches.size() + " games of " + nRuns + " runs across " + nThreads + " threads");
        long start = System.currentTimeMillis();
        int i = 1;
        for (Match match : matches) {
            System.out.println("\t" + i + ": " + match.ppt1.toString() + " vs. " + match.ppt2.toString());
            BattleResult result = BattleMT.play(match.ppt1, match.ppt2, nRuns, nThreads, false);
            match.battleResult = result;
            i++;
        }
        long end = System.currentTimeMillis();
        System.out.println("Tournament finished in " + (end - start) + " ms");
    }

    public static Set<Match> createTournament(PylosPlayerFactory ppf) {
        System.out.println("Creating round robin tournament of all players in " + ppf.getName() + " (n=" + ppf.getTypes().size() + ")");
        if (ppf.getTypes().stream().map(p -> p.toString()).distinct().count() != ppf.getTypes().size()) {
            throw new RuntimeException("All players in PylosPlayerFactory must have unique names");
        }

        Set<Match> matches = new HashSet<>();
        for (PylosPlayerType player : ppf.getTypes()) {
            matches.addAll(createCombinationsForPlayer(player, ppf.getTypes()));
        }
        return matches;
    }

    public static Set<Match> createCombinationsForPlayer(PylosPlayerType player, List<PylosPlayerType> allPlayers) {
        Set<Match> matches = new HashSet<>();
        for (PylosPlayerType opponent : allPlayers) {
            if (opponent != player) {
                matches.add(new Match(player, opponent));
            }
        }
        return matches;
    }

    public static void printWinsMatrix(Collection<BattleResult> results, List<PylosPlayerType> players) {
        System.out.println("Tournament results:");
        Integer[][] pointMatrix = new Integer[players.size()][players.size()];

        for (BattleResult result : results) {
            if (result != null) {
                int indexP1 = players.indexOf(result.p1);
                int indexP2 = players.indexOf(result.p2);
                pointMatrix[indexP1][indexP2] = result.p1StartP1Wins + result.p2StartP1Wins;
                pointMatrix[indexP2][indexP1] = result.p2StartP2Wins + result.p1StartP2Wins;
            } else {
                throw new RuntimeException("result is null");
            }
        }

        int spacing = Math.max(4, players.stream().mapToInt(p -> p.toString().length()).max().getAsInt());
        System.out.format("%" + spacing + "s", "\t");

        for (int i = 0; i < players.size(); i++) {
            System.out.format("%-" + spacing + "s", players.get(i).toString());
            System.out.print("\t");
        }
        System.out.println();
        for (int i = 0; i < players.size(); i++) {
            System.out.format("%-" + spacing + "s", players.get(i).toString());
            System.out.print("\t");
            for (int j = 0; j < players.size(); j++) {
                if (pointMatrix[i][j] == null) {
                    System.out.format("%" + spacing + "s", "\t");
                } else {
                    System.out.format("%" + spacing + "s", pointMatrix[i][j] + "\t");
                }
            }
            System.out.println();
        }
    }

    public static class Match {
        public PylosPlayerType ppt1;
        public PylosPlayerType ppt2;
        public BattleResult battleResult;

        public Match(PylosPlayerType ppt1, PylosPlayerType ppt2) {
            this.ppt1 = ppt1;
            this.ppt2 = ppt2;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Match that = (Match) o;
            return (Objects.equals(ppt1, that.ppt1) && Objects.equals(ppt2, that.ppt2))
                    || (Objects.equals(ppt1, that.ppt2) && Objects.equals(ppt2, that.ppt1));
        }

        @Override
        public int hashCode() {
            return Objects.hash(ppt1, ppt2) + Objects.hash(ppt2, ppt1);
        }
    }
}
