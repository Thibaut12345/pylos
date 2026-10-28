package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerObserver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** MCTS player with adversarial UCT selection, tactically biased rollouts and symmetry-aware states. */
public class StudentPlayer extends PylosPlayer {
    // Search limits scale with game progress (0 = start, 1 = end).
    private static final int EARLY_SIMULATIONS = 1_500;
    private static final int LATE_SIMULATIONS = 10_000;
    private static final long EARLY_MOVE_NS = 12_000_000L;
    private static final long LATE_MOVE_NS = 28_000_000L;
    private static final long EARLY_REMOVE_NS = 3_000_000L;
    private static final long LATE_REMOVE_NS = 7_000_000L;
    private static final long GAME_TIME_BUDGET_NS = 650_000_000L; // total own thinking time
    private static final double WIN_SCORE = 1.0, DRAW_SCORE = 0.0, LOSS_SCORE = -1.0;
    private static final int MAX_ROLLOUT_ACTIONS = 256;
    private final Random searchRandom = new Random();

    private PylosGameSimulator simulator;
    private PylosBoard board;
    private MCTSTree lightTree;
    private MCTSTree darkTree;
    private MCTSTree tree;
    private MCTSTree.Action chosenTreeAction;
    private PylosBoard budgetBoard;
    private long remainingGameNanos = GAME_TIME_BUDGET_NS;
    private int decisionsMade;

    @Override public void doMove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTree.Action chosenAction = searchBestAction();
        if (chosenAction == null || (chosenAction.type != MCTSTree.ActionType.PLACEMENT
                && chosenAction.type != MCTSTree.ActionType.MOVE)) {
            throw new IllegalStateException("MCTS did not return a legal move action");
        }
        getObserver().checkingMoveSphere(chosenAction.sphere, chosenAction.location);
        game.moveSphere(chosenAction.sphere, chosenAction.location);
        if (chosenTreeAction != null) tree.advanceRoot(chosenTreeAction);
    }

    @Override public void doRemove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTree.Action chosenAction = searchBestAction();
        if (chosenAction == null || chosenAction.type != MCTSTree.ActionType.REMOVE_FIRST)
            throw new IllegalStateException("MCTS did not return a legal first-removal action");
        getObserver().checkingRemoveSphere(chosenAction.sphere);
        game.removeSphere(chosenAction.sphere);
        if (chosenTreeAction != null) tree.advanceRoot(chosenTreeAction);
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
        if (chosenTreeAction != null) tree.advanceRoot(chosenTreeAction);
    }

    private void init(PylosGameState state, PylosBoard board) {
        if (budgetBoard != board) {
            budgetBoard = board;
            remainingGameNanos = GAME_TIME_BUDGET_NS;
            decisionsMade = 0;
            // Never reuse search statistics from a previous game.
            lightTree = null;
            darkTree = null;
        }
        this.board = board;
        this.simulator = new PylosGameSimulator(state, PLAYER_COLOR, board);
        ensureTreesInitialized();
        tree = treeFor(PLAYER_COLOR);
        tree.ensureRoot(simulator.getColor(), stateKey());
    }

    @Override public void init(PylosPlayerColor playerColor, PylosPlayer other, PylosPlayerObserver observer, Random random) {
        super.init(playerColor, other, observer, random);
        ensureTreesInitialized();
        tree = treeFor(PLAYER_COLOR);
    }

    private void ensureTreesInitialized() {
        if (lightTree != null && darkTree != null) return;
        long emptyBoardKey = initialStateKey();
        if (lightTree == null) lightTree = new MCTSTree(PylosPlayerColor.LIGHT, emptyBoardKey, PylosPlayerColor.LIGHT, searchRandom);
        if (darkTree == null) darkTree = new MCTSTree(PylosPlayerColor.DARK, emptyBoardKey, PylosPlayerColor.LIGHT, searchRandom);
    }

    private MCTSTree treeFor(PylosPlayerColor color) {
        return color == PylosPlayerColor.LIGHT ? lightTree : darkTree;
    }

    private static long initialStateKey() {
        // Empty board, LIGHT to move, MOVE phase: all encoded bits are zero.
        return 0L;
    }

    // Game progress is estimated from how many reserve spheres have been spent.
    // A max() with the decision count ensures progress does not move backwards
    // when a completed square sends a sphere back into reserve.
    private double gameProgress() {
        int total = 2 * board.SPHERES_PER_PLAYER;
        int inReserve = board.getReservesSize(PylosPlayerColor.LIGHT)
                + board.getReservesSize(PylosPlayerColor.DARK);
        double boardProgress = (double) (total - inReserve) / total;
        double turnProgress = Math.min(1.0, decisionsMade / 50.0);
        return Math.max(boardProgress, turnProgress);
    }

    private MCTSTree.Action searchBestAction() {
        MCTSTree.Node root = tree.getRoot();
        MCTSTree.Action directWin = findImmediateWin();
        if (directWin != null) {
            chosenTreeAction = null;
            return directWin;
        }

        long start = System.nanoTime();
        double progress = gameProgress();

        // Early game: fewer simulations / shorter turns.
        // Late game: more simulations / longer turns.
        int maxSimulations = EARLY_SIMULATIONS
                + (int) ((LATE_SIMULATIONS - EARLY_SIMULATIONS) * progress);
        long moveBudget = EARLY_MOVE_NS
                + (long) ((LATE_MOVE_NS - EARLY_MOVE_NS) * progress);
        long removeBudget = EARLY_REMOVE_NS
                + (long) ((LATE_REMOVE_NS - EARLY_REMOVE_NS) * progress);
        long perDecision = simulator.getState() == PylosGameState.MOVE
                ? moveBudget : removeBudget;

        // Keep enough thinking time for the rest of the game.
        // Late in the game there are fewer decisions left.
        int estimatedLeft = Math.max(4,
                (int) Math.round(28 * (1.0 - progress)));
        long allowed = Math.min(perDecision, remainingGameNanos / estimatedLeft);
        long deadline = start + Math.max(0, allowed);
        decisionsMade++;

        int iterations = 0;
        do {
            runOneIteration(root);
            iterations++;
        } while (iterations < maxSimulations && System.nanoTime() < deadline);

        remainingGameNanos = Math.max(0,
                remainingGameNanos - (System.nanoTime() - start));
        chosenTreeAction = tree.bestRootAction();
        if (chosenTreeAction == null) return null;
        PylosSymmetry.Position position = canonicalPosition();
        return PylosSymmetry.toActualAction(chosenTreeAction, legalActions(), position);
    }

    private MCTSTree.Action findImmediateWin() {
        // Only needed for MOVE states. Simulator terminal status changes after a move.
        if (simulator.getState() != PylosGameState.MOVE) return null;
        List<MCTSTree.Action> legal = legalActions();
        PylosGameState startingState = simulator.getState();
        PylosPlayerColor startingColor = simulator.getColor();
        MCTSTree.Action winning = null;
        for (MCTSTree.Action action : legal) {
            UndoRecord record = apply(action);
            try {
                if (simulator.getState() == PylosGameState.COMPLETED &&
                        simulator.getWinner() == PLAYER_COLOR) {
                    winning = action;
                    break;
                }
            } finally {
                undo(record);
            }
        }
        // Undo resets the phase and player but not simulator.winner.
        simulator = new PylosGameSimulator(startingState, startingColor, board);
        return winning;
    }

    private void runOneIteration(MCTSTree.Node root) {
        List<MCTSTree.Node> path = new ArrayList<>();
        List<UndoRecord> undo = new ArrayList<>();
        PylosGameState startingState = simulator.getState();
        PylosPlayerColor startingColor = simulator.getColor();
        MCTSTree.Node node = root;
        path.add(node);
        double reward;
        try {
            int depth = 0;
            while (!isTerminal()) {
                List<MCTSTree.Action> actualLegal = legalActions();
                if (actualLegal.isEmpty()) throw new IllegalStateException("No legal actions in " + simulator.getState());
                PylosSymmetry.Position position = canonicalPosition();
                // The canonical legal list is only required the FIRST time we expand a node.
                List<MCTSTree.Action> canonicalLegal = node.needsActions()
                        ? orderedCanonicalActions(actualLegal, position) : null;
                MCTSTree.Node child = tree.selectOrExpand(node, canonicalLegal);
                MCTSTree.Action actualAction = PylosSymmetry.toActualAction(child.getAction(), actualLegal, position);
                undo.add(apply(actualAction));
                // Previously visited nodes already have their observed state.
                // Rebuilding a canonical board key here would be expensive.
                if (!child.isObserved()) {
                    tree.observeState(child, stateKey(), simulator.getColor());
                }
                node = child;
                path.add(node);
                if (node.getVisits() == 0 || ++depth >= MAX_ROLLOUT_ACTIONS) break;
            }
            reward = rollout(undo);
        } finally {
            undoAll(undo);
            // Simulator.reset() does not clear its winner field after undo.
            // Rebuild the lightweight simulator to avoid stale terminal metadata.
            simulator = new PylosGameSimulator(startingState, startingColor, board);
        }
        tree.backpropagate(path, reward);
    }

    private double rollout(List<UndoRecord> undo) {
        int start = undo.size(), actions = 0;
        while (!isTerminal() && actions < MAX_ROLLOUT_ACTIONS) {
            // Avoid creating a whole list of Action objects at each rollout step.
            undo.add(apply(randomRolloutAction()));
            actions++;
        }
        double reward;
        if (simulator.getState() == PylosGameState.COMPLETED)
            reward = simulator.getWinner() == PLAYER_COLOR ? WIN_SCORE : LOSS_SCORE;
        else if (simulator.getState() == PylosGameState.DRAW) reward = DRAW_SCORE;
        else {
            // Non-terminal cut-off: approximate with remaining reserves.
            int mine = board.getReservesSize(PLAYER_COLOR);
            int theirs = board.getReservesSize(PLAYER_COLOR.other());
            reward = (mine - theirs) / 15.0;
        }
        for (int i = undo.size() - 1; i >= start; i--) undo(undo.remove(i));
        return reward;
    }


    // Rollout priorities: these are weights, NOT the final MCTS reward.
    private static final int SCORE_BASE = 1;
    private static final int SCORE_MOVE_EXISTING = 8;
    private static final int SCORE_COMPLETE_SQUARE = 35;
    private static final int SCORE_TWO_IN_SQUARE = 4;
    private static final int SCORE_ONE_IN_SQUARE = 1;
    private static final int SCORE_BLOCK_ENEMY = 5;

    /**
     * Pick a legal action proportionally to its tactical score.
     * Weighted reservoir sampling avoids constructing an action list.
     */
    private MCTSTree.Action randomRolloutAction() {
        PylosGameState state = simulator.getState();
        PylosPlayerColor color = simulator.getColor();

        if (state == PylosGameState.REMOVE_FIRST ||
                state == PylosGameState.REMOVE_SECOND) {
            PylosSphere selected = null;
            int count = 0;
            for (PylosSphere sphere : board.getSpheres(color)) {
                if (!sphere.isReserve() && sphere.canRemove()) {
                    if (searchRandom.nextInt(++count) == 0) selected = sphere;
                }
            }
            if (state == PylosGameState.REMOVE_SECOND &&
                    searchRandom.nextInt(count + 1) == 0)
                return MCTSTree.Action.pass();

            if (selected == null) throw new IllegalStateException("No removable sphere");
            return state == PylosGameState.REMOVE_FIRST
                    ? MCTSTree.Action.removeFirst(selected)
                    : MCTSTree.Action.removeSecond(selected);
        }

        if (state != PylosGameState.MOVE)
            throw new IllegalStateException("No rollout action in " + state);

        PylosSphere selectedSphere = null;
        PylosLocation selectedLocation = null;
        boolean selectedReserve = false;
        int totalWeight = 0;

        // First consider moving balls that are already on the board.
        for (PylosSphere sphere : board.getSpheres(color)) {
            if (sphere.isReserve() || !sphere.canMove()) continue;
            for (PylosLocation location : board.getLocations()) {
                if (!sphere.canMoveTo(location)) continue;

                int weight = scoreRolloutMove(sphere, location, color);
                totalWeight += weight;
                if (searchRandom.nextInt(totalWeight) < weight) {
                    selectedSphere = sphere;
                    selectedLocation = location;
                    selectedReserve = false;
                }
            }
        }

        // Then consider placing a new ball from the reserve.
        PylosSphere reserve = board.getReserve(color);
        if (reserve != null) {
            for (PylosLocation location : board.getLocations()) {
                if (!location.isUsable()) continue;

                int weight = scoreRolloutMove(reserve, location, color);
                totalWeight += weight;
                if (searchRandom.nextInt(totalWeight) < weight) {
                    selectedSphere = reserve;
                    selectedLocation = location;
                    selectedReserve = true;
                }
            }
        }

        if (selectedSphere == null)
            throw new IllegalStateException("No legal rollout move");

        return selectedReserve
                ? MCTSTree.Action.placement(selectedSphere, selectedLocation)
                : MCTSTree.Action.move(selectedSphere, selectedLocation);
    }

    private int scoreRolloutMove(PylosSphere sphere,
                                 PylosLocation destination,
                                 PylosPlayerColor color) {
        int score = SCORE_BASE;
        boolean fromReserve = sphere.isReserve();
        if (!fromReserve) score += SCORE_MOVE_EXISTING;

        PylosLocation source = fromReserve ? null : sphere.getLocation();
        PylosPlayerColor enemy = color.other();

        for (PylosSquare square : destination.getSquares()) {
            int mine = square.getInSquare(color);
            int theirs = square.getInSquare(enemy);

            // If an existing sphere leaves this same square, count that removal.
            if (source != null) {
                for (PylosLocation squareLocation : square.getLocations()) {
                    if (squareLocation == source) {
                        mine--;
                        break;
                    }
                }
            }

            if (theirs == 0) {
                if (mine == 3) score += SCORE_COMPLETE_SQUARE;
                else if (mine == 2) score += SCORE_TWO_IN_SQUARE;
                else if (mine == 1) score += SCORE_ONE_IN_SQUARE;
            } else if (theirs == 3 && mine == 0) {
                score += SCORE_BLOCK_ENEMY;
            }
        }

        return score;
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

    private List<MCTSTree.Action> orderedCanonicalActions(
            List<MCTSTree.Action> actual, PylosSymmetry.Position position) {
        // Sort actual moves first, then convert to canonical actions.
        // Low scores first: the tree removes the LAST unexpanded action.
        actual.sort((a, b) -> Integer.compare(tacticalScore(a), tacticalScore(b)));
        List<MCTSTree.Action> canonical = new ArrayList<>(actual.size());
        for (MCTSTree.Action action : actual)
            canonical.add(PylosSymmetry.toCanonicalAction(action, position));
        return canonical;
    }

    private int tacticalScore(MCTSTree.Action action) {
        switch (action.type) {
            case PLACEMENT:
            case MOVE:
                int score = scoreRolloutMove(
                        action.sphere, action.location, simulator.getColor());
                // Placing your final reserve sphere immediately loses in this engine.
                if (action.type == MCTSTree.ActionType.PLACEMENT &&
                        board.getReservesSize(simulator.getColor()) == 1)
                    score -= 100_000;
                return score;
            case REMOVE_FIRST:
            case REMOVE_SECOND:
                return 20;
            case PASS:
                return 1;
            default:
                return 1;
        }
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

    private long stateKey() { return canonicalPosition().getKey(); }

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
