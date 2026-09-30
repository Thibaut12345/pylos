package be.kuleuven.pylos.battle.data;

import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.player.PylosPlayerType;

import java.util.List;

public class PlayedGame {
    public final String lightPlayer;
    public final String darkPlayer;

    public final List<Long> boardHistory;

    public final int winner;

    public PlayedGame(List<Long> boardHistory, PylosPlayerType light, PylosPlayerType dark, PylosPlayerColor winner) {
        this.boardHistory = boardHistory;
        this.lightPlayer = light.toString();
        this.darkPlayer = dark.toString();
        this.winner = switch (winner) {
            case PylosPlayerColor.LIGHT -> 1;
            case PylosPlayerColor.DARK -> -1;
            case null -> 0;
        };
    }
}