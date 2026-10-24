package be.kuleuven.pylos.player.student;

import java.util.ArrayList;
import java.util.List;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;


public class MCTSTree {

    private MCTSNode root;

    public MCTSTree(State initialState) {
        root = new MCTSNode(initialState, null, null);
    }

    public MCTSNode getRoot() {
        return root;
    }

    public void advanceRoot(MCTSNode node) {
        root = node;
    }
}


class MCTSNode {

    private final State state;
    private final MCTSMove move;
    private final MCTSNode parent;
    private final List<MCTSNode> children;
    private int visitCount;
    private double winScore;


    public MCTSNode(State state, MCTSMove move, MCTSNode parent) {
        this.state = state;
        this.move = move;
        this.parent = parent;
        this.children = new ArrayList<>();
        this.visitCount = 0;
        this.winScore = 0.0;
    }


    public State getState() {
        return state;
    }

    public MCTSMove getMove() {
        return move;
    }

    public MCTSNode getParent() {
        return parent;
    }

    public List<MCTSNode> getChildren() {
        return children;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    public int getVisitCount() {
        return visitCount;
    }

    public double getWinScore() {
        return winScore;
    }

    public void incrementVisitCount() {
        visitCount++;
    }

    public void addWinScore(double score) {
        winScore += score;
    }

    public void addChild(MCTSNode child) {
        children.add(child);
    }
}


class State {
    private final PylosPlayerColor playerToMove;
    private final PylosGameState gameState;

    public State(PylosPlayerColor playerToMove, PylosGameState gameState) {
        this.playerToMove = playerToMove;
        this.gameState = gameState;
    }

    public PylosPlayerColor getPlayerToMove() {
        return playerToMove;
    }

    public PylosGameState getGameState() {
        return gameState;
    }

    public boolean isTerminal() {
        return gameState == PylosGameState.COMPLETED || gameState == PylosGameState.DRAW;
    }
}


class MCTSMove {

    public enum SecondRemoval {
        NONE,   // The move did not complete a square
        REMOVE, // The player removes a second sphere
        PASS    // The player passes the second removal
    }

    private final PylosPlayerColor color;


    private final int movedSphereId;
    private final int x;
    private final int y;
    private final int z;


    private final int firstRemovedSphereId;
    private final int secondRemovedSphereId;
    private final SecondRemoval secondRemoval;

    public MCTSMove(PylosPlayerColor color, int movedSphereId, int x, int y, int z, int firstRemovedSphereId, int secondRemovedSphereId, SecondRemoval secondRemoval) {

        this.color = color;
        this.movedSphereId = movedSphereId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.firstRemovedSphereId = firstRemovedSphereId;
        this.secondRemovedSphereId = secondRemovedSphereId;
        this.secondRemoval = secondRemoval;
    }

    public PylosPlayerColor getColor() {
        return color;
    }

    public int getMovedSphereId() {
        return movedSphereId;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getFirstRemovedSphereId() {
        return firstRemovedSphereId;
    }

    public int getSecondRemovedSphereId() {
        return secondRemovedSphereId;
    }

    public SecondRemoval getSecondRemoval() {
        return secondRemoval;
    }

}