package be.kuleuven.pylos.tournament;

import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.*;

/**
 * Stores the complete outcome of a Round-Robin tournament, including individual player standings,
 * head-to-head matchups, and fastchess-style formatted output methods.
 */
public record TournamentResult(List<PlayerStanding> standings, List<PylosPlayerType> players,
                               Map<String, PairStats> pairStatsMap, long durationMs, int totalGames, int totalPairs) {

    public TournamentResult(List<PlayerStanding> standings,
                            List<PylosPlayerType> players,
                            Map<String, PairStats> pairStatsMap,
                            long durationMs,
                            int totalGames,
                            int totalPairs) {
        this.standings = Collections.unmodifiableList(standings);
        this.players = Collections.unmodifiableList(players);
        this.pairStatsMap = Collections.unmodifiableMap(pairStatsMap);
        this.durationMs = durationMs;
        this.totalGames = totalGames;
        this.totalPairs = totalPairs;
    }

    public PlayerStanding getWinner() {
        return standings.isEmpty() ? null : standings.get(0);
    }

    public PairStats getPairStats(PylosPlayerType p1, PylosPlayerType p2) {
        PairStats stats = pairStatsMap.get(key(p1, p2));
        if (stats != null) return stats;
        return pairStatsMap.get(key(p2, p1));
    }

    public static String key(PylosPlayerType p1, PylosPlayerType p2) {
        return p1.toString() + " vs " + p2.toString();
    }

    /**
     * Prints the ranked leaderboard table in fastchess style with exact column alignment.
     */
    public void printLeaderboard() {
        System.out.println("\n================================= TOURNAMENT LEADERBOARD =================================");
        int maxNameLen = Math.max(12, players.stream().mapToInt(p -> p.toString().length()).max().orElse(12));
        String nameFormat = "%-" + maxNameLen + "s";

        String header = String.format(Locale.US,
                "Rank  " + nameFormat + "        Elo      +/-       nElo      +/-  Games  Score    Win%%   Draw%%  Ptnml(0-2)",
                "Player");
        System.out.println(header);
        System.out.println("-".repeat(header.length()));

        boolean hasNegative = standings.stream().anyMatch(s -> s.getElo() < -1e-6 || s.getNelo() < -1e-6);
        String rf = hasNegative ? "%+8.2f" : "%8.2f";

        for (PlayerStanding s : standings) {
            String eloStr = String.format(Locale.US, rf, s.getElo());
            String neloStr = String.format(Locale.US, rf, s.getNelo());

            System.out.printf(Locale.US,
                    "%4d  " + nameFormat + "   %8s  %7.2f   %8s  %7.2f  %5d  %5.1f  %5.1f%%  %6.1f%%  [%2d, %2d, %2d, %2d, %2d]%n",
                    s.getRank(),
                    s.getPlayer().toString(),
                    eloStr, s.getEloError(),
                    neloStr, s.getNeloError(),
                    s.getTotalGames(),
                    s.getPoints(),
                    s.getWinRate(),
                    s.getDrawRate(),
                    s.getPentaLL(), s.getPentaLD(), s.getPentaSplit(), s.getPentaWD(), s.getPentaWW()
            );
        }
        System.out.println("=".repeat(header.length()));
    }

    /**
     * Prints a head-to-head score cross-table matrix with fixed-width column alignment.
     */
    public void printCrossTable() {
        System.out.println("\n======================================= CROSS TABLE =======================================");
        int n = standings.size();
        int maxNameLen = Math.max(12, standings.stream().mapToInt(s -> s.getPlayer().toString().length()).max().orElse(12));
        String nameFormat = "%-" + maxNameLen + "s";

        StringBuilder sbHeader = new StringBuilder();
        sbHeader.append(String.format(" #   " + nameFormat + " ", "Player"));
        for (int j = 0; j < n; j++) {
            sbHeader.append(String.format("%10d", j + 1));
        }
        sbHeader.append("        Score");
        System.out.println(sbHeader.toString());
        System.out.println("-".repeat(sbHeader.length()));

        for (int i = 0; i < n; i++) {
            PlayerStanding row = standings.get(i);
            StringBuilder sbRow = new StringBuilder();
            sbRow.append(String.format(Locale.US, "%2d.  " + nameFormat + " ", i + 1, row.getPlayer().toString()));

            for (int j = 0; j < n; j++) {
                if (i == j) {
                    sbRow.append(String.format("%10s", "x"));
                } else {
                    PlayerStanding col = standings.get(j);
                    PairStats stats = getPairStats(row.getPlayer(), col.getPlayer());
                    if (stats != null) {
                        double pts = stats.getP1().toString().equals(row.getPlayer().toString()) ? stats.getPointsP1() : stats.getPointsP2();
                        int games = stats.getTotalGames();
                        String cell = String.format(Locale.US, "%.1f/%d", pts, games);
                        sbRow.append(String.format("%10s", cell));
                    } else {
                        sbRow.append(String.format("%10s", "-"));
                    }
                }
            }
            String totalScore = String.format(Locale.US, "%.1f/%d", row.getPoints(), row.getTotalGames());
            sbRow.append(String.format("   %10s", totalScore));
            System.out.println(sbRow.toString());
        }
        System.out.println("=".repeat(sbHeader.length()));
    }

    /**
     * Prints detailed fastchess-style match cards for all pairings.
     */
    public void printPairwiseSummaries() {
        System.out.println("\n============================= PAIRWISE MATCH SUMMARIES =============================");
        List<String> sortedKeys = new ArrayList<>(pairStatsMap.keySet());
        Collections.sort(sortedKeys);
        for (String k : sortedKeys) {
            PairStats ps = pairStatsMap.get(k);
            System.out.println("--------------------------------------------------------------------------------");
            System.out.println(ps.formatSummary());
        }
        System.out.println("--------------------------------------------------------------------------------");
    }

    /**
     * Prints complete tournament summary: metadata, leaderboard, cross table, and matchups.
     */
    public void printAll() {
        System.out.printf(Locale.US, "%nTournament finished in %.2f seconds (Total games: %d across %d pairs)%n",
                durationMs / 1000.0, totalGames, totalPairs);
        printLeaderboard();
        printCrossTable();
//        printPairwiseSummaries();
    }
}
