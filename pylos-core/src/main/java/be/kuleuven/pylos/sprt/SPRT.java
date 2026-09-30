package be.kuleuven.pylos.sprt;

import be.kuleuven.pylos.battle.data.PlayedGame;
import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sequential Probability Ratio Test (SPRT) with pentanomial statistics for Pylos.
 */
public class SPRT {

    static final double ALPHA = 0.05;
    static final double BETA = 0.05;
    static final int RANDOM_OPENING_MOVES = 6;

    public enum Status {
        H0_ACCEPTED, // Fail: Candidate failed to prove required Elo improvement
        H1_ACCEPTED  // Pass: Candidate proved required Elo improvement
    }

    private enum Winner { NEW, BASE, DRAW }

    private record PairResult(int pentaBin, double pairScore, Winner winner1, Winner winner2, PlayedGame pg1, PlayedGame pg2) {
    }

    public static void test(PylosPlayerType newPlayer, PylosPlayerType basePlayer, double elo0, double elo1) {
        int defaultThreads = Math.max(1, Runtime.getRuntime().availableProcessors() - 2);
        test(newPlayer, basePlayer, elo0, elo1, defaultThreads);
    }

    private static void test(PylosPlayerType newPlayer, PylosPlayerType basePlayer, double elo0, double elo1, int numThreads) {
        if (elo0 >= elo1) throw new IllegalArgumentException("elo0 must be < elo1");

        // 1. Precompute SPRT math boundaries (Wald's boundaries)
        double lowerBound = Math.log(SPRT.BETA / (1.0 - SPRT.ALPHA)); // A < 0 (H0 threshold)
        double upperBound = Math.log((1.0 - SPRT.BETA) / SPRT.ALPHA); // B > 0 (H1 threshold)

        // Target scores per game under H0 and H1
        double mu0 = 1.0 / (1.0 + Math.pow(10.0, -elo0 / 400.0));
        double mu1 = 1.0 / (1.0 + Math.pow(10.0, -elo1 / 400.0));

        int workerCount = Math.max(1, numThreads);

        System.out.println("Starting SPRT with " + workerCount + " worker threads...");
        System.out.println("Testing New: [" + newPlayer + "] against Base: [" + basePlayer + "]. Bounds: [" + String.format("%.4f", elo0) + ", " + String.format("%.4f", elo1) + "]");
        System.out.printf("Wald Bounds: [%.3f, %.3f] | Random opening moves: %d%n", lowerBound, upperBound, SPRT.RANDOM_OPENING_MOVES);

        // 2. Thread-safe communication channel & stop signal
        BlockingQueue<PairResult> queue = new LinkedBlockingQueue<>();
        AtomicBoolean stopSignal = new AtomicBoolean(false);

        // 3. Spawn fleet of worker threads
        Thread[] workers = new Thread[workerCount];
        for (int t = 0; t < workerCount; t++) {
            workers[t] = new Thread(() -> {
                Random threadRng = new Random();
                while (!stopSignal.get()) {
                    PairResult pairRes = playPair(newPlayer, basePlayer, threadRng);
                    try {
                        queue.put(pairRes);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "SPRT-Worker-" + t);
            workers[t].setDaemon(true);
            workers[t].start();
        }

        // 4. Main thread receives results, computes LLR, and prints live status
        int[] pentaCounts = new int[5]; // [LL, LD, DD/WL, WD, WW]
        int pairsPlayed = 0;
        double totalScore = 0.0;
        double llr;
        Status status = null;

        while (true) {
            PairResult result;
            try {
                result = queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            pentaCounts[result.pentaBin]++;
            totalScore += result.pairScore;
            pairsPlayed++;

            // Need a small sample before computing variance/LLR
            if (pairsPlayed < 10) continue;

            double totalGames = pairsPlayed * 2.0;
            double actualScore = totalScore / totalGames;

            // Calculate variance of pair distribution (normalized down to per-game scale)
            double variance = 0.0;
            for (int i = 0; i < 5; i++) {
                double p = (double) pentaCounts[i] / pairsPlayed;
                double scorePerGame = (i * 0.5) / 2.0; // 0.0, 0.25, 0.5, 0.75, 1.0
                variance += p * Math.pow(scorePerGame - actualScore, 2);
            }

            if (variance == 0.0) continue;

            // LLR formula scales linearly with total number of games
            llr = totalGames * (mu1 - mu0) * (2.0 * actualScore - mu0 - mu1) / (2.0 * variance);

            System.out.printf(
                    "\rPairs: %-5d (Games: %-5d) | [WW: %d, WD: %d, Split: %d, LD: %d, LL: %d] | LLR: %6.2f / [%.2f, %.2f]",
                    pairsPlayed, (int) totalGames,
                    pentaCounts[4], pentaCounts[3], pentaCounts[2], pentaCounts[1], pentaCounts[0],
                    llr, lowerBound, upperBound
            );
            System.out.flush();

            if (llr >= upperBound) {
                status = Status.H1_ACCEPTED;
                break;
            } else if (llr <= lowerBound) {
                status = Status.H0_ACCEPTED;
                break;
            }
        }

        // 5. Tell all running workers to wind down immediately
        stopSignal.set(true);
        for (Thread worker : workers) {
            worker.interrupt();
        }
        for (Thread worker : workers) {
            try {
                worker.join(1000);
            } catch (InterruptedException ignored) {}
        }

        System.out.println();
        System.out.println("\n[SPRT RESULT]: " + (status == Status.H1_ACCEPTED ? "ACCEPT (Passed!)" : "REJECT (Failed!)"));
    }

    /**
     * Executes one color-swapped game pair with identical random opening moves.
     */
    private static PairResult playPair(PylosPlayerType newPlayer, PylosPlayerType basePlayer, Random random) {
        // --------------------------------------------------------------------
        // Game 1: New Player is Light (starts), Base Player is Dark
        // --------------------------------------------------------------------
        PylosPlayer pNew_g1 = newPlayer.create();
        PylosPlayer pBase_g1 = basePlayer.create();
        PylosBoard board1 = new PylosBoard();
        PylosGame game1 = new PylosGame(board1, pNew_g1, pBase_g1, random);

        // Play random opening moves directly on the game and store chosen locations
        List<PylosLocation> openingLocations = new ArrayList<>();
        for (int i = 0; i < SPRT.RANDOM_OPENING_MOVES && game1.getState() == PylosGameState.MOVE; i++) {
            List<PylosLocation> usable = new ArrayList<>();
            for (PylosLocation loc : board1.getLocations()) {
                if (loc.isUsable()) usable.add(loc);
            }
            if (usable.isEmpty()) break;
            PylosLocation chosen = usable.get(random.nextInt(usable.size()));
            openingLocations.add(chosen);
            PylosPlayer current = (i % 2 == 0) ? pNew_g1 : pBase_g1;
            game1.moveSphere(board1.getReserve(current), chosen);
        }

        double score1 = 0.0;
        Winner winner1 = Winner.BASE;
        try {
            game1.play();
            if (game1.getState() == PylosGameState.DRAW) {
                score1 = 0.5;
                winner1 = Winner.DRAW;
            } else if (game1.getWinner() == pNew_g1) {
                score1 = 1.0;
                winner1 = Winner.NEW;
            }
        } catch (PylosGameCrashedException ge) {
            if (ge.getCurrentPlayer() != pNew_g1) {
                score1 = 1.0;
                winner1 = Winner.NEW;
            }
        }

        PylosPlayerColor winningColor1 = null;
        if (winner1 == Winner.NEW) {
            winningColor1 = PylosPlayerColor.LIGHT;
        } else if (winner1 == Winner.BASE) {
            winningColor1 = PylosPlayerColor.DARK;
        }

        PlayedGame pg1 = new PlayedGame(game1.getBoardHistory(), newPlayer, basePlayer, winningColor1);

        // --------------------------------------------------------------------
        // Game 2: Base Player is Light (starts), New Player is Dark
        // Replay the exact same opening locations from Game 1
        // --------------------------------------------------------------------
        PylosPlayer pNew_g2 = newPlayer.create();
        PylosPlayer pBase_g2 = basePlayer.create();
        PylosBoard board2 = new PylosBoard();
        PylosGame game2 = new PylosGame(board2, pBase_g2, pNew_g2, random);

        for (int i = 0; i < openingLocations.size() && game2.getState() == PylosGameState.MOVE; i++) {
            PylosLocation loc = openingLocations.get(i);
            PylosLocation loc2 = board2.getBoardLocation(loc.X, loc.Y, loc.Z);
            PylosPlayer current = (i % 2 == 0) ? pBase_g2 : pNew_g2;
            game2.moveSphere(board2.getReserve(current), loc2);
        }

        double score2 = 0.0;
        Winner winner2 = Winner.BASE;
        try {
            game2.play();
            if (game2.getState() == PylosGameState.DRAW) {
                score2 = 0.5;
                winner2 = Winner.DRAW;
            } else if (game2.getWinner() == pNew_g2) {
                score2 = 1.0;
                winner2 = Winner.NEW;
            }
        } catch (PylosGameCrashedException ge) {
            if (ge.getCurrentPlayer() != pNew_g2) {
                score2 = 1.0;
                winner2 = Winner.NEW;
            }
        }

        PylosPlayerColor winningColor2 = null;
        if (winner2 == Winner.NEW) {
            winningColor2 = PylosPlayerColor.DARK;
        } else if (winner2 == Winner.BASE) {
            winningColor2 = PylosPlayerColor.LIGHT;
        }

        PlayedGame pg2 = new PlayedGame(game2.getBoardHistory(), basePlayer, newPlayer, winningColor2);

        // Convert the 2 discrete matches into a unified pentanomial pair result
        double pairScore = score1 + score2;
        int bin = (int) Math.round(pairScore * 2.0);

        return new PairResult(bin, pairScore, winner1, winner2, pg1, pg2);
    }
}
