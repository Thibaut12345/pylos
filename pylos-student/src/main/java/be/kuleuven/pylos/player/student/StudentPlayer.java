package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** MCTS player with adversarial UCT selection, tactically biased rollouts and symmetry-aware states. */
public class StudentPlayer extends PylosPlayer {
    private static final int NUMBER_OF_SIMULATIONS = 512;
    private static final double WIN_SCORE = 1.0, DRAW_SCORE = 0.0, LOSS_SCORE = -1.0;
    private static final int MAX_ROLLOUT_ACTIONS = 1024;
    private final Random searchRandom = new Random();

    private PylosGameSimulator simulator;
    private PylosBoard board;
    private MCTSTree tree;
    private MCTSTree.Action chosenTreeAction;

    @Override public void doMove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTree.Action chosenAction = searchBestAction();
        if (chosenAction == null || (chosenAction.type != MCTSTree.ActionType.PLACEMENT
                && chosenAction.type != MCTSTree.ActionType.MOVE)) {
            throw new IllegalStateException("MCTS did not return a legal move action");
        }
        getObserver().checkingMoveSphere(chosenAction.sphere, chosenAction.location);
        game.moveSphere(chosenAction.sphere, chosenAction.location);
        tree.advanceRoot(chosenTreeAction);
    }

    @Override public void doRemove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTree.Action chosenAction = searchBestAction();
        if (chosenAction == null || chosenAction.type != MCTSTree.ActionType.REMOVE_FIRST)
            throw new IllegalStateException("MCTS did not return a legal first-removal action");
        getObserver().checkingRemoveSphere(chosenAction.sphere);
        game.removeSphere(chosenAction.sphere);
        tree.advanceRoot(chosenTreeAction);
    }

    @Override public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTree.Action chosenAction = searchBestAction();
        if (chosenAction == null) throw new IllegalStateException("MCTS returned no second-removal action");
        if (chosenAction.type == MCTSTree.ActionType.PASS) game.pass();
        else if (chosenAction.type == MCTSTree.ActionType.REMOVE_SECOND) {
            getObserver().checkingRemoveSphere(chosenAction.sphere);
            game.removeSphere(chosenAction.sphere);
        } else throw new IllegalStateException("Unexpected second-removal action: " + chosenAction);
        tree.advanceRoot(chosenTreeAction);
    }

    private void init(PylosGameState state, PylosBoard board) {
        this.board = board;
        this.simulator = new PylosGameSimulator(state, PLAYER_COLOR, board);
        String key = stateKey();
        if (tree == null || tree.getRewardPerspective() != PLAYER_COLOR) {
            tree = new MCTSTree(PLAYER_COLOR, key, searchRandom);
        } else {
            tree.ensureRoot(simulator.getColor(), key);
        }
    }

    @Override public void init(PylosPlayerColor playerColor, PylosPlayer other,
                               PylosPlayerObserver observer, Random random) {
        super.init(playerColor, other, observer, random);
        tree = null;
    }

    /** Returns a live engine action and retains its canonical descriptor for tree advancement. */
    private MCTSTree.Action searchBestAction() {
        MCTSTree.Node root = tree.getRoot();
        for (int i = 0; i < NUMBER_OF_SIMULATIONS; i++) runOneIteration(root);
        chosenTreeAction = tree.bestRootAction();
        if (chosenTreeAction == null) return null;
        PylosSymmetry.Position position = canonicalPosition();
        return PylosSymmetry.toActualAction(chosenTreeAction, legalActions(), position);
    }

    private void runOneIteration(MCTSTree.Node root) {
        List<MCTSTree.Node> path = new ArrayList<>();
        List<UndoRecord> undo = new ArrayList<>();
        MCTSTree.Node node = root;
        path.add(node);
        PylosGameState startState = simulator.getState();
        PylosPlayerColor startColor = simulator.getColor();
        String before = stateKey();
        double reward;
        try {
            int depth = 0;
            while (!isTerminal()) {
                List<MCTSTree.Action> actualLegal = legalActions();
                if (actualLegal.isEmpty()) throw new IllegalStateException("No legal actions in " + simulator.getState());
                PylosSymmetry.Position position = canonicalPosition();
                List<MCTSTree.Action> canonicalLegal = canonicalActions(actualLegal, position);
                MCTSTree.Node child = tree.selectOrExpand(node, canonicalLegal);
                MCTSTree.Action actualAction = PylosSymmetry.toActualAction(child.getAction(), actualLegal, position);
                undo.add(apply(actualAction));
                child.setObservedState(stateKey(), simulator.getColor());
                node = child;
                path.add(node);
                if (node.getVisits() == 0 || ++depth >= MAX_ROLLOUT_ACTIONS) break;
            }
            reward = rollout(undo);
        } finally {
            undoAll(undo);
            String after = stateKey();
            if (!before.equals(after)) throw new IllegalStateException("Board restoration failed: " + before + " != " + after);
            simulator = new PylosGameSimulator(startState, startColor, board);
        }
        tree.backpropagate(path, reward);
    }

    private double rollout(List<UndoRecord> undo) {
        int start = undo.size(), actions = 0;
        while (!isTerminal() && actions < MAX_ROLLOUT_ACTIONS) {
            List<MCTSTree.Action> legal = legalActions();
            if (legal.isEmpty()) throw new IllegalStateException("No rollout actions in " + simulator.getState());
            undo.add(apply(chooseRolloutAction(legal)));
            actions++;
        }
        double reward;
        if (simulator.getState() == PylosGameState.COMPLETED)
            reward = simulator.getWinner() == PLAYER_COLOR ? WIN_SCORE : LOSS_SCORE;
        else if (simulator.getState() == PylosGameState.DRAW) reward = DRAW_SCORE;
        else reward = DRAW_SCORE;
        for (int i = undo.size() - 1; i >= start; i--) undo(undo.remove(i));
        return reward;
    }

    /** Immediate wins are mandatory; otherwise prefer square completion 75% of the time. */
    private MCTSTree.Action chooseRolloutAction(List<MCTSTree.Action> available) {
        PylosPlayerColor mover = simulator.getColor();
        List<MCTSTree.Action> squareMoves = new ArrayList<>(), safe = new ArrayList<>();
        MCTSTree.Action winning = null;
        for (MCTSTree.Action action : available) {
            UndoRecord record = apply(action);
            PylosGameState state = simulator.getState();
            PylosPlayerColor winner = simulator.getWinner();
            if (state == PylosGameState.COMPLETED && winner == mover) winning = action;
            if (state != PylosGameState.COMPLETED || winner == null || winner == mover) safe.add(action);
            if (state == PylosGameState.REMOVE_FIRST) squareMoves.add(action);
            undo(record);
            simulator = new PylosGameSimulator(record.state, record.color, board);
            if (winning != null) break;
        }
        if (winning != null) return winning;
        if (!squareMoves.isEmpty() && searchRandom.nextDouble() < 0.75)
            return squareMoves.get(searchRandom.nextInt(squareMoves.size()));
        List<MCTSTree.Action> choices = safe.isEmpty() ? available : safe;
        return choices.get(searchRandom.nextInt(choices.size()));
    }

    private List<MCTSTree.Action> legalActions() {
        PylosGameState state = simulator.getState();
        PylosPlayerColor color = simulator.getColor();
        List<MCTSTree.Action> actions = new ArrayList<>();
        if (state == PylosGameState.MOVE) {
            for (PylosSphere sphere : board.getSpheres(color)) {
                if (sphere.isReserve() || !sphere.canMove()) continue;
                for (PylosLocation location : board.getLocations())
                    if (sphere.canMoveTo(location)) actions.add(MCTSTree.Action.move(sphere, location));
            }
            PylosSphere reserve = board.getReserve(color);
            if (reserve != null) for (PylosLocation location : board.getLocations())
                if (location.isUsable()) actions.add(MCTSTree.Action.placement(reserve, location));
        } else if (state == PylosGameState.REMOVE_FIRST) {
            for (PylosSphere sphere : board.getSpheres(color))
                if (!sphere.isReserve() && sphere.canRemove()) actions.add(MCTSTree.Action.removeFirst(sphere));
        } else if (state == PylosGameState.REMOVE_SECOND) {
            for (PylosSphere sphere : board.getSpheres(color))
                if (!sphere.isReserve() && sphere.canRemove()) actions.add(MCTSTree.Action.removeSecond(sphere));
            actions.add(MCTSTree.Action.pass());
        }
        return actions;
    }

    private List<MCTSTree.Action> canonicalActions(List<MCTSTree.Action> actual, PylosSymmetry.Position position) {
        List<MCTSTree.Action> canonical = new ArrayList<>(actual.size());
        for (MCTSTree.Action action : actual) canonical.add(PylosSymmetry.toCanonicalAction(action, position));
        return canonical;
    }

    private PylosSymmetry.Position canonicalPosition() {
        return PylosSymmetry.canonicalize(board, simulator.getState(), simulator.getColor());
    }

    private UndoRecord apply(MCTSTree.Action action) {
        PylosGameState state = simulator.getState();
        PylosPlayerColor color = simulator.getColor();
        PylosLocation location = action.sphere == null ? null : action.sphere.getLocation();
        switch (action.type) {
            case PLACEMENT: case MOVE: simulator.moveSphere(action.sphere, action.location); break;
            case REMOVE_FIRST: case REMOVE_SECOND: simulator.removeSphere(action.sphere); break;
            case PASS: simulator.pass(); break;
            default: throw new IllegalStateException("Unknown action: " + action.type);
        }
        return new UndoRecord(action, state, color, location);
    }

    private void undo(UndoRecord r) {
        switch (r.action.type) {
            case PLACEMENT: simulator.undoAddSphere(r.action.sphere, r.state, r.color); break;
            case MOVE: simulator.undoMoveSphere(r.action.sphere, r.location, r.state, r.color); break;
            case REMOVE_FIRST: simulator.undoRemoveFirstSphere(r.action.sphere, r.location, r.state, r.color); break;
            case REMOVE_SECOND: simulator.undoRemoveSecondSphere(r.action.sphere, r.location, r.state, r.color); break;
            case PASS: simulator.undoPass(r.state, r.color); break;
            default: throw new IllegalStateException("Unknown action: " + r.action.type);
        }
    }

    private void undoAll(List<UndoRecord> records) {
        for (int i = records.size() - 1; i >= 0; i--) undo(records.get(i));
        records.clear();
    }

    private boolean isTerminal() {
        PylosGameState s = simulator.getState();
        return s == PylosGameState.COMPLETED || s == PylosGameState.DRAW || s == PylosGameState.ABORTED;
    }

    private String stateKey() { return canonicalPosition().getKey(); }

    private static final class UndoRecord {
        private final MCTSTree.Action action;
        private final PylosGameState state;
        private final PylosPlayerColor color;
        private final PylosLocation location;
        private UndoRecord(MCTSTree.Action action, PylosGameState state, PylosPlayerColor color, PylosLocation location) {
            this.action = action; this.state = state; this.color = color; this.location = location;
        }
    }
}
