package be.kuleuven.pylos.game;

import be.kuleuven.pylos.player.PylosPlayer;

public class PylosGameCrashedException extends RuntimeException{
    PylosPlayer currentPlayer;
    PylosPlayer opponentPlayer;

    Exception exception;

    public PylosGameCrashedException(PylosPlayer currentPlayer, PylosPlayer opponentPlayer, Exception exception) {
        this.currentPlayer = currentPlayer;
        this.opponentPlayer = opponentPlayer;
        this.exception = exception;
    }

    public PylosPlayer getCurrentPlayer() {
        return currentPlayer;
    }

    public PylosPlayer getOpponentPlayer() {
        return opponentPlayer;
    }

    public Exception getException() {
        return exception;
    }
}
