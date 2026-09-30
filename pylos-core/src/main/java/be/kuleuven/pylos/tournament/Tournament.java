package be.kuleuven.pylos.tournament;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerFactory;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class Tournament {
    public static TournamentResult run(TournamentConfig config) {
        PylosPlayerFactory factory = config.playerFactory();
        List<PylosPlayerType> players = config.getPlayers();
        int nPlayers = players.size();

        // Validate player uniqueness
        long distinctCount = players.stream().map(Object::toString).distinct().count();
        if (distinctCount != nPlayers) {
            throw new IllegalArgumentException("All tournament players must have unique names");
        }

        // Initialize head-to-head match stats
        Map<String, PairStats> pairStatsMap = new LinkedHashMap<>();
        List<PairStats> pairStatsList = new ArrayList<>();

        for (int i = 0; i < nPlayers; i++) {
            for (int j = i + 1; j < nPlayers; j++) {
                PylosPlayerType p1 = players.get(i);
                PylosPlayerType p2 = players.get(j);
                PairStats ps = new PairStats(p1, p2);
                pairStatsMap.put(TournamentResult.key(p1, p2), ps);
                pairStatsList.add(ps);
            }
        }

        int totalPairMatches = pairStatsList.size() * config.pairRounds();
        int totalGames = totalPairMatches * 2;

        if (config.verbose()) {
            System.out.println("================================================================================");
            System.out.println("Starting Round-Robin Tournament: " + factory.getName());
            System.out.printf(Locale.US, "Players: %d | Pairings: %d | Pair rounds: %d | Total games: %d | Threads: %d%n",
                    nPlayers, pairStatsList.size(), config.pairRounds(), totalGames, config.threads());
            System.out.printf(Locale.US, "Fair opening swap: %d moves | Statistical Model: Pentanomial Normalized Elo%n",
                    config.randomOpeningMoves());
            System.out.println("================================================================================");
        }

        long startTime = System.currentTimeMillis();
        AtomicInteger completedPairs = new AtomicInteger(0);

        try (ExecutorService pool = Executors.newFixedThreadPool(config.threads())) {
            List<Future<?>> futures = new ArrayList<>();

            for (PairStats pairStats : pairStatsList) {
                for (int r = 0; r < config.pairRounds(); r++) {
                    futures.add(pool.submit(() -> {
                        playGamePair(pairStats, config.randomOpeningMoves());
                        int done = completedPairs.incrementAndGet();
                        if (config.verbose() && (done % 5 == 0 || done == totalPairMatches)) {
                            double pct = ((double) done / totalPairMatches) * 100.0;
                            System.out.printf(Locale.US, "\r[Tournament Progress]: %d / %d pairs completed (%.1f%%)",
                                    done, totalPairMatches, pct);
                            System.out.flush();
                        }
                    }));
                }
            }

            pool.shutdown();
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Tournament interrupted", e);
        }

        if (config.verbose()) {
            System.out.println(); // newline after progress
        }
        long durationMs = System.currentTimeMillis() - startTime;

        // Build aggregate statistics and standings for all players
        List<PlayerStanding> standings = computeStandings(players, pairStatsMap, config);

        TournamentResult result = new TournamentResult(
                standings, players, pairStatsMap, durationMs, totalGames, totalPairMatches
        );

        if (config.verbose()) {
            result.printAll();
        }

        return result;
    }

    /**
     * Executes one color-swapped game pair with identical random opening moves.
     */
    private static void playGamePair(PairStats pairStats, int randomOpeningMoves) {
        PylosPlayerType p1Type = pairStats.getP1();
        PylosPlayerType p2Type = pairStats.getP2();
        Random rng = new Random();

        // --------------------------------------------------------------------
        // Game 1: P1 is Light (starts), P2 is Dark
        // --------------------------------------------------------------------
        PylosPlayer p1_g1 = p1Type.create();
        PylosPlayer p2_g1 = p2Type.create();
        PylosBoard board1 = new PylosBoard();
        PylosGame game1 = new PylosGame(board1, p1_g1, p2_g1, rng);

        List<PylosLocation> openingLocations = new ArrayList<>();
        for (int i = 0; i < randomOpeningMoves && game1.getState() == PylosGameState.MOVE; i++) {
            List<PylosLocation> usable = new ArrayList<>();
            for (PylosLocation loc : board1.getLocations()) {
                if (loc.isUsable()) usable.add(loc);
            }
            if (usable.isEmpty()) break;
            PylosLocation chosen = usable.get(rng.nextInt(usable.size()));
            openingLocations.add(chosen);
            PylosPlayer current = (i % 2 == 0) ? p1_g1 : p2_g1;
            game1.moveSphere(board1.getReserve(current), chosen);
        }

        double score1 = 0.0;
        try {
            game1.play();
            if (game1.getState() == PylosGameState.DRAW) {
                score1 = 0.5;
            } else if (game1.getWinner() == p1_g1) {
                score1 = 1.0;
            }
        } catch (PylosGameCrashedException ge) {
            if (ge.getCurrentPlayer() != p1_g1) {
                score1 = 1.0;
            }
        }

        // --------------------------------------------------------------------
        // Game 2: P2 is Light (starts), P1 is Dark
        // Replay the exact same opening locations from Game 1
        // --------------------------------------------------------------------
        PylosPlayer p1_g2 = p1Type.create();
        PylosPlayer p2_g2 = p2Type.create();
        PylosBoard board2 = new PylosBoard();
        PylosGame game2 = new PylosGame(board2, p2_g2, p1_g2, rng);

        for (int i = 0; i < openingLocations.size() && game2.getState() == PylosGameState.MOVE; i++) {
            PylosLocation loc = openingLocations.get(i);
            PylosLocation loc2 = board2.getBoardLocation(loc.X, loc.Y, loc.Z);
            PylosPlayer current = (i % 2 == 0) ? p2_g2 : p1_g2;
            game2.moveSphere(board2.getReserve(current), loc2);
        }

        double score2 = 0.0;
        try {
            game2.play();
            if (game2.getState() == PylosGameState.DRAW) {
                score2 = 0.5;
            } else if (game2.getWinner() == p1_g2) {
                score2 = 1.0;
            }
        } catch (PylosGameCrashedException ge) {
            if (ge.getCurrentPlayer() != p1_g2) {
                score2 = 1.0;
            }
        }

        // Record results from P1's perspective
        pairStats.recordPair(score1, score2);
    }

    /**
     * Aggregates match statistics across all opponents and solves Bradley-Terry tournament ratings.
     */
    private static List<PlayerStanding> computeStandings(List<PylosPlayerType> players, Map<String, PairStats> pairStatsMap, TournamentConfig config) {
        int n = players.size();
        double[][] pointMatrix = new double[n][n];
        int[][] gameMatrix = new int[n][n];

        // Fill head-to-head point and game matrices
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                PylosPlayerType p1 = players.get(i);
                PylosPlayerType p2 = players.get(j);
                PairStats ps = pairStatsMap.get(TournamentResult.key(p1, p2));
                if (ps != null) {
                    pointMatrix[i][j] = ps.getPointsP1();
                    pointMatrix[j][i] = ps.getPointsP2();
                    gameMatrix[i][j] = ps.getTotalGames();
                    gameMatrix[j][i] = ps.getTotalGames();
                }
            }
        }

        // Solve Bradley-Terry tournament Elo ratings
        double[] btElo = NormalizedElo.solveBradleyTerryElo(n, pointMatrix, gameMatrix);

        // Compute player raw statistics and error margins
        double[] rawNelo = new double[n];
        double[] eloErr = new double[n];
        double[] neloErr = new double[n];

        int[] totalGames = new int[n];
        int[] totalPairs = new int[n];
        int[] wins = new int[n];
        int[] draws = new int[n];
        int[] losses = new int[n];
        double[] points = new double[n];

        int[] pentaWW = new int[n];
        int[] pentaWD = new int[n];
        int[] pentaWL = new int[n];
        int[] pentaDD = new int[n];
        int[] pentaLD = new int[n];
        int[] pentaLL = new int[n];

        for (int i = 0; i < n; i++) {
            PylosPlayerType p = players.get(i);

            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                PylosPlayerType opp = players.get(j);
                PairStats ps = pairStatsMap.get(TournamentResult.key(p, opp));
                boolean isP1 = (ps != null);
                if (ps == null) {
                    ps = pairStatsMap.get(TournamentResult.key(opp, p));
                }
                if (ps == null) continue;

                totalGames[i] += ps.getTotalGames();
                totalPairs[i] += ps.getTotalPairs();

                if (isP1) {
                    wins[i] += ps.getWinsP1();
                    losses[i] += ps.getWinsP2();
                    draws[i] += ps.getDraws();
                    points[i] += ps.getPointsP1();
                    pentaWW[i] += ps.getPentaWW();
                    pentaWD[i] += ps.getPentaWD();
                    pentaWL[i] += ps.getPentaWL();
                    pentaDD[i] += ps.getPentaDD();
                    pentaLD[i] += ps.getPentaLD();
                    pentaLL[i] += ps.getPentaLL();
                } else {
                    wins[i] += ps.getWinsP2();
                    losses[i] += ps.getWinsP1();
                    draws[i] += ps.getDraws();
                    points[i] += ps.getPointsP2();
                    pentaWW[i] += ps.getPentaLL();
                    pentaWD[i] += ps.getPentaLD();
                    pentaWL[i] += ps.getPentaWL();
                    pentaDD[i] += ps.getPentaDD();
                    pentaLD[i] += ps.getPentaWD();
                    pentaLL[i] += ps.getPentaWW();
                }
            }

            double score = totalPairs[i] > 0 ? NormalizedElo.calcScore(pentaWW[i], pentaWD[i], pentaWL[i], pentaDD[i], pentaLD[i], pentaLL[i]) : 0.5;
            double variance = totalPairs[i] > 0 ? NormalizedElo.calcVariance(pentaWW[i], pentaWD[i], pentaWL[i], pentaDD[i], pentaLD[i], pentaLL[i], score) : 0.125;
            double variancePerPair = totalPairs[i] > 0 ? variance / totalPairs[i] : 0.0;
            double sd = Math.sqrt(variancePerPair);

            double sUpper = score + NormalizedElo.CI95_Z_SCORE * sd;
            double sLower = score - NormalizedElo.CI95_Z_SCORE * sd;
            eloErr[i] = (NormalizedElo.scoreToEloDiff(sUpper) - NormalizedElo.scoreToEloDiff(sLower)) / 2.0;

            rawNelo[i] = totalPairs[i] > 0 ? NormalizedElo.scoreToNeloDiff(score, variance) : 0.0;
            neloErr[i] = (NormalizedElo.CI95_Z_SCORE * NormalizedElo.CONSTANT_800_LN10) / Math.sqrt(2.0 * Math.max(1, totalPairs[i]));
        }

        // Zero-center raw nElo across all tournament players
        double neloMean = Arrays.stream(rawNelo).average().orElse(0.0);
        for (int i = 0; i < n; i++) {
            rawNelo[i] -= neloMean;
        }

        // Determine rating shifts based on config (anchorPlayer, autoPositive, baseRating)
        double eloShift = 0.0;
        double neloShift = 0.0;

        if (config.anchorPlayerName() != null && config.anchorRating() != null) {
            int anchorIdx = -1;
            for (int i = 0; i < n; i++) {
                if (players.get(i).toString().equalsIgnoreCase(config.anchorPlayerName())) {
                    anchorIdx = i;
                    break;
                }
            }
            if (anchorIdx != -1) {
                eloShift = config.anchorRating() - btElo[anchorIdx];
                neloShift = config.anchorRating() - rawNelo[anchorIdx];
            }
        }

        // Build standings with final shifted ratings
        List<PlayerStanding> standings = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            standings.add(new PlayerStanding(
                    0, players.get(i), totalGames[i], totalPairs[i],
                    wins[i], draws[i], losses[i], points[i],
                    pentaWW[i], pentaWD[i], pentaWL[i], pentaDD[i], pentaLD[i], pentaLL[i],
                    btElo[i] + eloShift, eloErr[i], rawNelo[i] + neloShift, neloErr[i]
            ));
        }

        // Sort descending by points, then by nElo
        Collections.sort(standings);

        // Assign ranks (1-indexed)
        List<PlayerStanding> rankedStandings = new ArrayList<>();
        for (int r = 0; r < standings.size(); r++) {
            PlayerStanding s = standings.get(r);
            rankedStandings.add(new PlayerStanding(
                    r + 1, s.getPlayer(), s.getTotalGames(), s.getTotalPairs(),
                    s.getWins(), s.getDraws(), s.getLosses(), s.getPoints(),
                    s.getPentaWW(), s.getPentaWD(), s.getPentaWL(), s.getPentaDD(), s.getPentaLD(), s.getPentaLL(),
                    s.getElo(), s.getEloError(), s.getNelo(), s.getNeloError()
            ));
        }

        return rankedStandings;
    }
}
