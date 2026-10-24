package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;

import java.util.ArrayList;
import java.util.List;

/** Student Pylos player using adversarial UCT Monte Carlo Tree Search. */
public class StudentPlayer extends PylosPlayer {
    private static final int NUMBER_OF_SIMULATIONS = 1000;
    private static final double WIN_SCORE = 1.0;
    private static final double DRAW_SCORE = 0.0;
    private static final double LOSS_SCORE = -1.0;

    private static final int MAX_ROLLOUT_ACTIONS = 512;

    private PylosGameSimulator simulator;
    private PylosBoard board;
    private MCTSTree tree;
    private MCTSTree.Action chosenAction;

    public StudentPlayer() { }

    @Override
    public void doMove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        //System.out.println("initDoMove:");
        //System.out.println("AI color: " + PLAYER_COLOR);
        //System.out.println("Simulator color: " + simulator.getColor());
        //System.out.println("Simulator state: " + simulator.getState());
        //System.out.println("Simulator state key: " + stateKey());
        chosenAction = searchBestAction();
        if (chosenAction == null || (chosenAction.type != MCTSTree.ActionType.PLACEMENT
                && chosenAction.type != MCTSTree.ActionType.MOVE)) {
            throw new IllegalStateException("MCTS did not return a legal move action");
        }

        getObserver().checkingMoveSphere(chosenAction.sphere, chosenAction.location);
        game.moveSphere(chosenAction.sphere, chosenAction.location);
        advanceTreeAfterRealAction();
    }

    @Override
    public void doRemove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        //System.out.println("initDoReMove:");
        //System.out.println("AI color: " + PLAYER_COLOR);
        //System.out.println("Simulator color: " + simulator.getColor());
        //System.out.println("Simulator state: " + simulator.getState());
        //System.out.println("Simulator state key: " + stateKey());
        chosenAction = searchBestAction();
        if (chosenAction == null || chosenAction.type != MCTSTree.ActionType.REMOVE_FIRST) {
            throw new IllegalStateException("MCTS did not return a legal first-removal action");
        }

        getObserver().checkingRemoveSphere(chosenAction.sphere);
        game.removeSphere(chosenAction.sphere);
        advanceTreeAfterRealAction();
    }

    @Override
    public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        //System.out.println("initDoReMoveOrPass:");
        //System.out.println("AI color: " + PLAYER_COLOR);
        //System.out.println("Simulator color: " + simulator.getColor());
        //System.out.println("Simulator state: " + simulator.getState());
        //System.out.println("Simulator state key: " + stateKey());
        chosenAction = searchBestAction();
        if (chosenAction == null) {
            throw new IllegalStateException("MCTS did not return a legal second-removal action");
        }

        if (chosenAction.type == MCTSTree.ActionType.PASS) {
            game.pass();
        } else if (chosenAction.type == MCTSTree.ActionType.REMOVE_SECOND) {
            getObserver().checkingRemoveSphere(chosenAction.sphere);
            game.removeSphere(chosenAction.sphere);
        } else {
            throw new IllegalStateException("Unexpected action during second removal: " + chosenAction);
        }
        advanceTreeAfterRealAction();
    }

    private void init(PylosGameState state, PylosBoard board) {
        this.board = board;
        this.simulator = new PylosGameSimulator(state, PLAYER_COLOR, board);
        String stateKey = stateKey();

        if (tree == null) {
            tree = new MCTSTree(PLAYER_COLOR, stateKey, getRandom());
        } else {
            tree.ensureRoot(simulator.getColor(), stateKey);
        }
    }

    private MCTSTree.Action searchBestAction() {
        MCTSTree.Node root = tree.getRoot();
        for (int i = 0; i < NUMBER_OF_SIMULATIONS; i++) {
            runOneIteration(root);
        }
        return tree.bestRootAction();
    }

    private void runOneIteration(MCTSTree.Node root) {
        List<MCTSTree.Node> path = new ArrayList<>();
        List<UndoRecord> undoRecords = new ArrayList<>();
        MCTSTree.Node node = root;
        path.add(node);

        PylosGameState startingState = simulator.getState();
        PylosPlayerColor startingColor = simulator.getColor();

        // Capture the board and game state before the iteration.
        String stateKeyBefore = stateKey();

        double reward = 0.0;

        try {
            int depth = 0;

            while (!isTerminal()) {
                List<MCTSTree.Action> legalActions = legalActions();

                if (legalActions.isEmpty()) {
                    throw new IllegalStateException(
                            "No legal actions in state " + simulator.getState()
                    );
                }

                MCTSTree.Node child = tree.selectOrExpand(node, legalActions);

                undoRecords.add(apply(child.getAction()));

                child.setObservedState(stateKey(), simulator.getColor());

                node = child;
                path.add(node);
                depth++;

                if (node.getVisits() == 0 || depth >= MAX_ROLLOUT_ACTIONS) {
                    break;
                }
            }

            reward = rollout(undoRecords);

        } finally {
            undoAll(undoRecords);

            // Compare immediately after undoing all simulated actions.
            String stateKeyAfter = stateKey();

//            if (!stateKeyBefore.equals(stateKeyAfter)) {
//                System.out.println("BOARD RESTORATION FAILED");
//                System.out.println("Before: " + stateKeyBefore);
//                System.out.println("After:  " + stateKeyAfter);
//            } else {
//                System.out.println("Board restoration OK");
//            }

            // Recreate the simulator only after checking restoration.
            simulator = new PylosGameSimulator(
                    startingState, startingColor, board
            );
        }

        tree.backpropagate(path, reward);
    }

    /** Runs a random legal playout from the current search leaf and restores it. */
    private double rollout(List<UndoRecord> undoRecords) {
        int rolloutStart = undoRecords.size();
        int actions = 0;
        while (!isTerminal() && actions < MAX_ROLLOUT_ACTIONS) {
            List<MCTSTree.Action> actionsAvailable = legalActions();
            if (actionsAvailable.isEmpty()) {
                throw new IllegalStateException("No legal actions during rollout in " + simulator.getState());
            }
            MCTSTree.Action action = actionsAvailable.get(getRandom().nextInt(actionsAvailable.size()));
            undoRecords.add(apply(action));
            actions++;
        }

        double result;
        if (simulator.getState() == PylosGameState.COMPLETED) {
            result = simulator.getWinner() == PLAYER_COLOR ? WIN_SCORE : LOSS_SCORE;
        } else if (simulator.getState() == PylosGameState.DRAW) {
            result = DRAW_SCORE;
        } else {
            result = LOSS_SCORE;
        }

        // Rollout actions must be undone before returning to the tree leaf;
        // the tree-path actions are undone by runOneIteration's finally block.
        for (int i = undoRecords.size() - 1; i >= rolloutStart; i--) {
            undo(undoRecords.remove(i));
        }
        return result;
    }

    private List<MCTSTree.Action> legalActions() {
        PylosGameState state = simulator.getState();
        PylosPlayerColor color = simulator.getColor();
        List<MCTSTree.Action> actions = new ArrayList<>();

        if (state == PylosGameState.MOVE) {
            // Upward moves use already-placed spheres. Generate placements
            // separately using getReserve(), matching the framework's API and
            // avoiding duplicate equivalent actions for every reserve sphere.
            for (PylosSphere sphere : board.getSpheres(color)) {
                if (sphere.isReserve() || !sphere.canMove()) {
                    continue;
                }
                for (PylosLocation location : board.getLocations()) {
                    if (sphere.canMoveTo(location)) {
                        actions.add(MCTSTree.Action.move(sphere, location));
                    }
                }
            }
            PylosSphere reserveSphere = board.getReserve(color);
            if (reserveSphere != null) {
                for (PylosLocation location : board.getLocations()) {
                    if (location.isUsable()) {
                        actions.add(MCTSTree.Action.placement(reserveSphere, location));
                    }
                }
            }
        } else if (state == PylosGameState.REMOVE_FIRST) {
            for (PylosSphere sphere : board.getSpheres(color)) {
                if (!sphere.isReserve() && sphere.canRemove()) {
                    actions.add(MCTSTree.Action.removeFirst(sphere));
                }
            }
        } else if (state == PylosGameState.REMOVE_SECOND) {
            for (PylosSphere sphere : board.getSpheres(color)) {
                if (!sphere.isReserve() && sphere.canRemove()) {
                    actions.add(MCTSTree.Action.removeSecond(sphere));
                }
            }
            actions.add(MCTSTree.Action.pass());
        }
        return actions;
    }

    private UndoRecord apply(MCTSTree.Action action) {
        PylosGameState previousState = simulator.getState();
        PylosPlayerColor previousColor = simulator.getColor();
        PylosLocation previousLocation = action.sphere == null ? null : action.sphere.getLocation();

        switch (action.type) {
            case PLACEMENT:
            case MOVE:
                simulator.moveSphere(action.sphere, action.location);
                break;
            case REMOVE_FIRST:
            case REMOVE_SECOND:
                simulator.removeSphere(action.sphere);
                break;
            case PASS:
                simulator.pass();
                break;
            default:
                throw new IllegalStateException("Unknown action type: " + action.type);
        }
        return new UndoRecord(action, previousState, previousColor, previousLocation);
    }

    private void undo(UndoRecord record) {
        MCTSTree.Action action = record.action;
        switch (action.type) {
            case PLACEMENT:
                simulator.undoAddSphere(action.sphere, record.previousState, record.previousColor);
                break;
            case MOVE:
                simulator.undoMoveSphere(action.sphere, record.previousLocation,
                        record.previousState, record.previousColor);
                break;
            case REMOVE_FIRST:
                simulator.undoRemoveFirstSphere(action.sphere, record.previousLocation,
                        record.previousState, record.previousColor);
                break;
            case REMOVE_SECOND:
                simulator.undoRemoveSecondSphere(action.sphere, record.previousLocation,
                        record.previousState, record.previousColor);
                break;
            case PASS:
                simulator.undoPass(record.previousState, record.previousColor);
                break;
            default:
                throw new IllegalStateException("Unknown action type: " + action.type);
        }
    }

    private void undoAll(List<UndoRecord> records) {
        for (int i = records.size() - 1; i >= 0; i--) {
            undo(records.get(i));
        }
        records.clear();
    }

    private boolean isTerminal() {
        PylosGameState state = simulator.getState();
        return state == PylosGameState.COMPLETED
                || state == PylosGameState.DRAW
                || state == PylosGameState.ABORTED;
    }

    private String stateKey() {
        StringBuilder key = new StringBuilder();
        key.append(simulator.getState()).append('|').append(simulator.getColor()).append('|');
        for (PylosLocation location : board.getLocations()) {
            PylosSphere sphere = location.getSphere();
            if (sphere == null) {
                key.append("-;");
            } else {
                key.append(sphere.PLAYER_COLOR).append(':').append(sphere.ID).append(';');
            }
        }
        return key.toString();
    }

    private void advanceTreeAfterRealAction() {
        // The simulator used for search has been restored to the pre-action
        // state. The selected child's post-action state was recorded during
        // search, so advance using that stored state rather than reading the
        // stale simulator phase/color after the real game has changed the board.
            //System.out.println("Before advancing tree:");
            //System.out.println("AI color: " + PLAYER_COLOR);
            //System.out.println("Simulator color: " + simulator.getColor());
            //System.out.println("Simulator state: " + simulator.getState());
            //System.out.println("Simulator state key: " + stateKey());

            tree.advanceRoot(chosenAction);

            //System.out.println("After advancing tree:");
            //System.out.println("Root player: " + tree.getRoot().getColor());
            //System.out.println("Root state key: " + tree.getRoot().getStateKey());
    }

    private static final class UndoRecord {
        private final MCTSTree.Action action;
        private final PylosGameState previousState;
        private final PylosPlayerColor previousColor;
        private final PylosLocation previousLocation;

        private UndoRecord(MCTSTree.Action action, PylosGameState previousState, PylosPlayerColor previousColor, PylosLocation previousLocation) {
            this.action = action;
            this.previousState = previousState;
            this.previousColor = previousColor;
            this.previousLocation = previousLocation;
        }
    }
}
