package be.kuleuven.pylos.battle;

import be.kuleuven.pylos.battle.data.PlayedGame;
import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.ArrayList;
import java.util.Random;

public class Battle {

    public static BattleResult play(PylosPlayerType ppt1, PylosPlayerType ppt2, int runs) {
        return play(ppt1, ppt2, runs, true);
    }

    public static BattleResult play(PylosPlayerType ppt1, PylosPlayerType ppt2, int runs, boolean print) {
        if (runs % 2 != 0) {
            throw new IllegalArgumentException("Please specify an even number of runs");
        }

        Random random = new Random();
        PylosPlayer p1 = ppt1.create();
        PylosPlayer p2 = ppt2.create();

        ArrayList<PlayedGame> playedGames = new ArrayList<>();

        long totalPlayTime = 0;

        int p1StartP1Win = 0;
        int p1StartP2Win = 0;
        int p1StartDraw = 0;

        if (print) System.out.println("Starting battle: " + runs + "x [" + ppt1 + "] vs [" + ppt2 + "]");

        for (int i = 0; i < runs / 2; i++) {
            if (print) System.out.print("*");
            PylosBoard board = new PylosBoard();
            PylosGame game = new PylosGame(board, p1, p2, random);
            long startTime = System.currentTimeMillis();
            try{
                game.play();
                if (game.getState() == PylosGameState.DRAW) {
                    playedGames.add(new PlayedGame(game.getBoardHistory(), ppt1, ppt2, null));
                    p1StartDraw++;
                } else {
                    if (game.getWinner() == p1) {
                        playedGames.add(new PlayedGame(game.getBoardHistory(), ppt1, ppt2, PylosPlayerColor.LIGHT));
                        p1StartP1Win++;
                    } else {
                        playedGames.add(new PlayedGame(game.getBoardHistory(), ppt1, ppt2, PylosPlayerColor.DARK));
                        p1StartP2Win++;
                    }
                }
            }
            catch(PylosGameCrashedException ge){
                System.err.println("Game crashed during turn of " + ge.getCurrentPlayer().getClass().getName() + ", giving forfeit");
                ge.getException().printStackTrace();
                if (ge.getCurrentPlayer() == p1){
                    p1StartP2Win++;
                }
                else{
                    p1StartP1Win++;
                }
            }
            long playTime = System.currentTimeMillis() - startTime;
            totalPlayTime += playTime;
        }

        int p2StartP1Win = 0;
        int p2StartP2Win = 0;
        int p2StartDraw = 0;

        for (int i = 0; i < runs / 2; i++) {
            if (print) System.out.print("*");
            PylosBoard board = new PylosBoard();
            PylosGame game = new PylosGame(board, p2, p1, random);
            long startTime = System.currentTimeMillis();
            try {
                game.play();
                if (game.getState() == PylosGameState.DRAW) {
                    playedGames.add(new PlayedGame(game.getBoardHistory(), ppt2, ppt1, null));
                    p2StartDraw++;
                } else {
                    if (game.getWinner() == p1) {
                        playedGames.add(new PlayedGame(game.getBoardHistory(), ppt2, ppt1, PylosPlayerColor.DARK));
                        p2StartP1Win++;
                    } else {
                        playedGames.add(new PlayedGame(game.getBoardHistory(), ppt2, ppt1, PylosPlayerColor.LIGHT));
                        p2StartP2Win++;
                    }
                }
            }
            catch (PylosGameCrashedException ge){
                System.err.println("Game crashed during turn of " + ge.getCurrentPlayer().getClass().getName() + ", giving forfeit");
                ge.getException().printStackTrace();
                if (ge.getCurrentPlayer() == p2) {
                    p2StartP1Win++;
                } else {
                    p2StartP2Win++;
                }
            }
            long playTime = System.currentTimeMillis() - startTime;
            totalPlayTime += playTime;
        }

        if (print) System.out.println();

        BattleResult battleResult = new BattleResult(ppt1, ppt2, totalPlayTime, p1StartP1Win, p1StartDraw, p1StartP2Win, p2StartP1Win, p2StartDraw, p2StartP2Win, playedGames);

        if (print) {
            battleResult.print();
        }

        return battleResult;
    }
}

