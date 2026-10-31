package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerObserver;
import java.util.*;

/** MCTS player with adversarial UCT selection, tactically biased rollouts and symmetry-aware states. */
public class StudentTimeBudgetPlayer extends PylosPlayer {
    // Search limits scale with game progress (0 = start, 1 = end).
    private static final int EARLY_SIMULATIONS = 10_000;
    private static final int LATE_SIMULATIONS = 100_000;
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
    private MCTSTreeTimeBudget lightTree;
    private MCTSTreeTimeBudget darkTree;
    private MCTSTreeTimeBudget tree;
    private MCTSTreeTimeBudget.Action chosenTreeAction;
    private PylosBoard budgetBoard;
    private long remainingGameNanos = GAME_TIME_BUDGET_NS;
    private int decisionsMade;

    @Override public void doMove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTreeTimeBudget.Action chosenAction = searchBestAction();
        if (chosenAction == null || (chosenAction.type != MCTSTreeTimeBudget.ActionType.PLACEMENT
                && chosenAction.type != MCTSTreeTimeBudget.ActionType.MOVE)) {
            throw new IllegalStateException("MCTS did not return a legal move action");
        }
        getObserver().checkingMoveSphere(chosenAction.sphere, chosenAction.location);
        game.moveSphere(chosenAction.sphere, chosenAction.location);
        if (chosenTreeAction != null) tree.advanceRoot(chosenTreeAction);
    }

    @Override public void doRemove(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTreeTimeBudget.Action chosenAction = searchBestAction();
        if (chosenAction == null || chosenAction.type != MCTSTreeTimeBudget.ActionType.REMOVE_FIRST)
            throw new IllegalStateException("MCTS did not return a legal first-removal action");
        getObserver().checkingRemoveSphere(chosenAction.sphere);
        game.removeSphere(chosenAction.sphere);
        if (chosenTreeAction != null) tree.advanceRoot(chosenTreeAction);
    }

    @Override public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {
        init(game.getState(), board);
        MCTSTreeTimeBudget.Action chosenAction = searchBestAction();
        if (chosenAction == null) throw new IllegalStateException("MCTS returned no second-removal action");
        if (chosenAction.type == MCTSTreeTimeBudget.ActionType.PASS) game.pass();
        else if (chosenAction.type == MCTSTreeTimeBudget.ActionType.REMOVE_SECOND) {
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
        if (lightTree == null) lightTree = new MCTSTreeTimeBudget(PylosPlayerColor.LIGHT, emptyBoardKey, PylosPlayerColor.LIGHT, searchRandom);
        if (darkTree == null) darkTree = new MCTSTreeTimeBudget(PylosPlayerColor.DARK, emptyBoardKey, PylosPlayerColor.LIGHT, searchRandom);
    }

    private MCTSTreeTimeBudget treeFor(PylosPlayerColor color) {
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

    private MCTSTreeTimeBudget.Action searchBestAction() {
        MCTSTreeTimeBudget
                .Node root = tree.getRoot();
        MCTSTreeTimeBudget.Action directWin = findImmediateWin();
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
        PylosSymmetryTimeBudget.Position position = canonicalPosition();
        return PylosSymmetryTimeBudget.toActualAction(chosenTreeAction, legalActions(), position);
    }

    private MCTSTreeTimeBudget.Action findImmediateWin() {
        // Only needed for MOVE states. Simulator terminal status changes after a move.
        if (simulator.getState() != PylosGameState.MOVE) return null;
        List<MCTSTreeTimeBudget.Action> legal = legalActions();
        PylosGameState startingState = simulator.getState();
        PylosPlayerColor startingColor = simulator.getColor();
        MCTSTreeTimeBudget.Action winning = null;
        for (MCTSTreeTimeBudget.Action action : legal) {
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

    private void runOneIteration(MCTSTreeTimeBudget.Node root) {
        List<MCTSTreeTimeBudget.Node> path = new ArrayList<>();
        List<UndoRecord> undo = new ArrayList<>();
        PylosGameState startingState = simulator.getState();
        PylosPlayerColor startingColor = simulator.getColor();
        MCTSTreeTimeBudget.Node node = root;
        path.add(node);
        double reward;
        try {
            int depth = 0;
            while (!isTerminal()) {
                List<MCTSTreeTimeBudget.Action> actualLegal = legalActions();
                if (actualLegal.isEmpty()) throw new IllegalStateException("No legal actions in " + simulator.getState());
                PylosSymmetryTimeBudget.Position position = canonicalPosition();
                // The canonical legal list is only required the FIRST time we expand a node.
                List<MCTSTreeTimeBudget.Action> canonicalLegal = node.needsActions()
                        ? orderedCanonicalActions(actualLegal, position) : null;
                MCTSTreeTimeBudget.Node child = tree.selectOrExpand(node, canonicalLegal);
                MCTSTreeTimeBudget.Action actualAction = PylosSymmetryTimeBudget.toActualAction(child.getAction(), actualLegal, position);
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
    private MCTSTreeTimeBudget.Action randomRolloutAction() {
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
                return MCTSTreeTimeBudget.Action.pass();

            if (selected == null) throw new IllegalStateException("No removable sphere");
            return state == PylosGameState.REMOVE_FIRST
                    ? MCTSTreeTimeBudget.Action.removeFirst(selected)
                    : MCTSTreeTimeBudget.Action.removeSecond(selected);
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
                ? MCTSTreeTimeBudget.Action.placement(selectedSphere, selectedLocation)
                : MCTSTreeTimeBudget.Action.move(selectedSphere, selectedLocation);
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

    private List<MCTSTreeTimeBudget.Action> legalActions() {
        PylosGameState state = simulator.getState();
        PylosPlayerColor color = simulator.getColor();
        List<MCTSTreeTimeBudget.Action> actions = new ArrayList<>();
        if (state == PylosGameState.MOVE) {
            for (PylosSphere sphere : board.getSpheres(color)) {
                if (sphere.isReserve() || !sphere.canMove()) continue;
                for (PylosLocation location : board.getLocations())
                    if (sphere.canMoveTo(location)) actions.add(MCTSTreeTimeBudget.Action.move(sphere, location));
            }
            PylosSphere reserve = board.getReserve(color);
            if (reserve != null) for (PylosLocation location : board.getLocations())
                if (location.isUsable()) actions.add(MCTSTreeTimeBudget.Action.placement(reserve, location));
        } else if (state == PylosGameState.REMOVE_FIRST) {
            for (PylosSphere sphere : board.getSpheres(color))
                if (!sphere.isReserve() && sphere.canRemove()) actions.add(MCTSTreeTimeBudget.Action.removeFirst(sphere));
        } else if (state == PylosGameState.REMOVE_SECOND) {
            for (PylosSphere sphere : board.getSpheres(color))
                if (!sphere.isReserve() && sphere.canRemove()) actions.add(MCTSTreeTimeBudget.Action.removeSecond(sphere));
            actions.add(MCTSTreeTimeBudget.Action.pass());
        }
        return actions;
    }

    private List<MCTSTreeTimeBudget.Action> orderedCanonicalActions(
            List<MCTSTreeTimeBudget.Action> actual, PylosSymmetryTimeBudget.Position position) {
        // Sort actual moves first, then convert to canonical actions.
        // Low scores first: the tree removes the LAST unexpanded action.
        actual.sort((a, b) -> Integer.compare(tacticalScore(a), tacticalScore(b)));
        List<MCTSTreeTimeBudget.Action> canonical = new ArrayList<>(actual.size());
        for (MCTSTreeTimeBudget.Action action : actual)
            canonical.add(PylosSymmetryTimeBudget.toCanonicalAction(action, position));
        return canonical;
    }

    private int tacticalScore(MCTSTreeTimeBudget.Action action) {
        switch (action.type) {
            case PLACEMENT:
            case MOVE:
                int score = scoreRolloutMove(
                        action.sphere, action.location, simulator.getColor());
                // Placing your final reserve sphere immediately loses in this engine.
                if (action.type == MCTSTreeTimeBudget.ActionType.PLACEMENT &&
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

    private PylosSymmetryTimeBudget.Position canonicalPosition() {
        return PylosSymmetryTimeBudget.canonicalize(board, simulator.getState(), simulator.getColor());
    }

    private UndoRecord apply(MCTSTreeTimeBudget.Action action) {
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
        private final MCTSTreeTimeBudget.Action action;
        private final PylosGameState state;
        private final PylosPlayerColor color;
        private final PylosLocation location;
        private UndoRecord(MCTSTreeTimeBudget.Action action, PylosGameState state, PylosPlayerColor color, PylosLocation location) {
            this.action = action; this.state = state; this.color = color; this.location = location;
        }
    }
}

final class MCTSTreeTimeBudget {
    public static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(1.0);
    public enum ActionType { PLACEMENT, MOVE, REMOVE_FIRST, REMOVE_SECOND, PASS }


    public static final class Action {
        public final ActionType type;
        public final PylosSphere sphere;
        public final PylosLocation location;
        private final boolean canonical;
        private final int fromIndex;
        private final int toIndex;

        private Action(ActionType type, PylosSphere sphere, PylosLocation location, boolean canonical, int fromIndex, int toIndex) {
            this.type = type;
            this.sphere = sphere;
            this.location = location;
            this.canonical = canonical;
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
        }
        public static Action placement(PylosSphere s, PylosLocation l) { return actual(ActionType.PLACEMENT, s, l); }
        public static Action move(PylosSphere s, PylosLocation l) { return actual(ActionType.MOVE, s, l); }
        public static Action removeFirst(PylosSphere s) { return actual(ActionType.REMOVE_FIRST, s, null); }
        public static Action removeSecond(PylosSphere s) { return actual(ActionType.REMOVE_SECOND, s, null); }
        public static Action pass() { return actual(ActionType.PASS, null, null); }
        private static Action actual(ActionType type, PylosSphere sphere, PylosLocation location) {
            return new Action(type, sphere, location, false, -1, -1);
        }
        static Action canonical(ActionType type, int fromIndex, int toIndex) {
            return new Action(type, null, null, true, fromIndex, toIndex);
        }
        public boolean sameAs(Action other) {
            if (other == null || type != other.type || canonical != other.canonical) return false;
            if (canonical) return fromIndex == other.fromIndex && toIndex == other.toIndex;
            return sphere == other.sphere && location == other.location;
        }
    }

    public static final class Node {
        private Node parent;
        private final Action action;
        private final List<Node> children = new ArrayList<>();
        private List<Action> unexpandedActions;
        private int visits;
        private double totalReward;
        private long stateKey;
        private boolean observed;
        private PylosPlayerColor playerToMove;
        private Node(Node parent, Action action, long key, PylosPlayerColor player) {
            this.parent = parent; this.action = action; stateKey = key; playerToMove = player; observed = player != null;
        }
        public Action getAction() { return action; }
        public int getVisits() { return visits; }
        public double getMeanReward() { return visits == 0 ? 0.0 : totalReward / visits; }
        public PylosPlayerColor getColor() { return playerToMove; }
        public long getStateKey() { return stateKey; }
        public boolean isObserved() { return observed; }
        public boolean needsActions() { return unexpandedActions == null; }
        public void setObservedState(long key, PylosPlayerColor color) {
            if (color == null) throw new IllegalArgumentException("Observed state and player are required");
            if (observed) {
                if (stateKey != key || playerToMove != color)
                    throw new IllegalStateException("Node state mismatch: same canonical action produced a different state");
                return;
            }
            stateKey = key; playerToMove = color; observed = true;
        }
    }

    private final Random random;
    private final double explorationConstant;
    private final PylosPlayerColor rewardPerspective;

    private final Node persistentRoot;
    private Node root;
    private final Map<Long, Node> stateIndex = new HashMap<>();


    public MCTSTreeTimeBudget(PylosPlayerColor rewardPerspective, long key, PylosPlayerColor playerToMove, Random random) {
        this(rewardPerspective, key, playerToMove, random, DEFAULT_EXPLORATION_CONSTANT);
    }

    public MCTSTreeTimeBudget(PylosPlayerColor rewardPerspective, long key, PylosPlayerColor playerToMove, Random random, double explorationConstant) {
        if (rewardPerspective == null || playerToMove == null || random == null)
            throw new IllegalArgumentException("Reward perspective, root player, key and RNG are required");
        if (explorationConstant < 0.0 || Double.isNaN(explorationConstant))
            throw new IllegalArgumentException("Invalid exploration constant");
        this.random = random;
        this.explorationConstant = explorationConstant;
        this.rewardPerspective = rewardPerspective;
        persistentRoot = new Node(null, null, key, playerToMove);
        root = persistentRoot;
        indexState(persistentRoot);
    }

    public Node getRoot() { return root; }

    public void ensureRoot(PylosPlayerColor player, long key) {
        if (player == null) throw new IllegalArgumentException("Root player and key are required");
        if (player == root.playerToMove && key == root.stateKey) return;
        Node match = stateIndex.get(key);
        if (match == null) {
            match = new Node(null, null, key, player);
            indexState(match);
        }
        root = match;
    }

    public void observeState(Node node, long key, PylosPlayerColor player) {
        if (node == null) throw new IllegalArgumentException("Node is required");
        node.setObservedState(key, player);
        indexState(node);
    }

    private void indexState(Node node) {
        if (node.observed)
            stateIndex.putIfAbsent(node.stateKey, node);
    }

    public Node selectOrExpand(Node node, List<Action> legal) {
        if (node == null) throw new IllegalArgumentException("Node is required");
        if (node.unexpandedActions == null) {
            if (legal == null || legal.isEmpty()) throw new IllegalArgumentException("Legal actions are required for expansion");
            node.unexpandedActions = new ArrayList<>(legal);
            // StudentPlayer supplies actions in ascending tactical priority.
            // Removing from the end expands the most promising move first.
        }
        if (!node.unexpandedActions.isEmpty()) {
            Node child = new Node(node, node.unexpandedActions.remove(node.unexpandedActions.size() - 1), 0L, null);
            node.children.add(child); return child;
        }
        Node best = null; double bestValue = Double.NEGATIVE_INFINITY;
        double logVisits = Math.log(Math.max(1, node.visits));
        for (Node child : node.children) {
            if (child.visits == 0) return child;
            double exploitation = child.totalReward / child.visits;
            if (node.playerToMove != rewardPerspective) exploitation = -exploitation;
            double exploration = explorationConstant * Math.sqrt(logVisits / child.visits);
            double value = exploitation + exploration;
            if (value > bestValue) { bestValue = value; best = child; }
        }
        if (best == null) throw new IllegalStateException("No child available for UCT selection");
        return best;
    }

    public void backpropagate(List<Node> path, double reward) {
        if (path == null) throw new IllegalArgumentException("Path is required");
        if (Double.isNaN(reward) || reward < -1.0 || reward > 1.0) throw new IllegalArgumentException("Reward must be in [-1,1]");
        for (Node node : path) { node.visits++; node.totalReward += reward; }
    }

    public Action bestRootAction() {
        Node best = null; double bestMean = Double.NEGATIVE_INFINITY;
        for (Node child : root.children) {
            double mean = child.visits == 0 ? Double.NEGATIVE_INFINITY : child.totalReward / child.visits;
            if (best == null || child.visits > best.visits || (child.visits == best.visits && mean > bestMean)) {
                best = child; bestMean = mean;
            }
        }
        return best == null ? null : best.action;
    }

    public void advanceRoot(Action canonicalAction) {
        if (canonicalAction == null || !canonicalAction.canonical) throw new IllegalArgumentException("A canonical tree action is required");
        Node selected = null;
        for (Node child : root.children) if (child.action != null && child.action.sameAs(canonicalAction)) { selected = child; break; }
        if (selected == null) throw new IllegalStateException("Selected canonical action is not a child of the current root");
        if (!selected.observed) throw new IllegalStateException("Selected child has no observed state");
        root = selected;
        indexState(selected);
    }
}

final class PylosSymmetryTimeBudget {
    private static final int LOCATION_COUNT = 30;
    private static final int SYMMETRY_COUNT = 8;
    private static final int[][][] COORDINATES = createCoordinateSequences();

    private PylosSymmetryTimeBudget() { }

    public static final class Position {
        private final long key;
        private final int symmetry;

        private Position(long key, int symmetry) {
            this.key = key;
            this.symmetry = symmetry;
        }

        public long getKey() { return key; }

        public int indexOf(PylosLocation location) {
            if (location == null) {
                return -1;
            }

            return COORDINATE_INDEX[symmetry]
                    [location.X][location.Y][location.Z];
        }

        private static final int[][][][] COORDINATE_INDEX = createCoordinateIndex();

        private static int[][][][] createCoordinateIndex() {
            int[][][][] index = new int[SYMMETRY_COUNT][4][4][4];

            for (int symmetry = 0; symmetry < SYMMETRY_COUNT; symmetry++) {
                for (int i = 0; i < LOCATION_COUNT; i++) {
                    int x = COORDINATES[symmetry][i][0];
                    int y = COORDINATES[symmetry][i][1];
                    int z = COORDINATES[symmetry][i][2];

                    index[symmetry][x][y][z] = i;
                }
            }

            return index;
        }
    }

    public static Position canonicalize(PylosBoard board, PylosGameState state, PylosPlayerColor playerToMove) {
        if (board == null || state == null || playerToMove == null) throw new IllegalArgumentException("Board, state and player-to-move are required");

        int bestSymmetry = 0;
        long best = occupancy(board, 0);
        for (int symmetry = 1; symmetry < SYMMETRY_COUNT; symmetry++) {
            long candidate = occupancy(board, symmetry);
            if (Long.compareUnsigned(candidate, best) < 0) {
                best = candidate;
                bestSymmetry = symmetry;
            }
        }
        // 30 cells x 2 bits = 60 bits; 3 bits for phase, 1 bit for player.
        long key = best | ((long) state.ordinal() << 60);
        if (playerToMove == PylosPlayerColor.DARK) key |= Long.MIN_VALUE;
        return new Position(key, bestSymmetry);
    }

    public static MCTSTreeTimeBudget.Action toCanonicalAction(MCTSTreeTimeBudget.Action action, Position position) {
        if (action == null || position == null) throw new IllegalArgumentException("Action and position are required");
        int from = -1;
        int to = -1;
        switch (action.type) {
            case PLACEMENT:
                to = position.indexOf(action.location);
                break;
            case MOVE:
                from = position.indexOf(action.sphere.getLocation());
                to = position.indexOf(action.location);
                break;
            case REMOVE_FIRST:
            case REMOVE_SECOND:
                from = position.indexOf(action.sphere.getLocation());
                break;
            case PASS:
                break;
            default:
                throw new IllegalStateException("Unsupported action type: " + action.type);
        }
        return MCTSTreeTimeBudget.Action.canonical(action.type, from, to);
    }

    public static MCTSTreeTimeBudget.Action toActualAction(
            MCTSTreeTimeBudget.Action canonicalAction,
            Iterable<MCTSTreeTimeBudget.Action> actualLegalActions,
            Position position
    ) {
        if (canonicalAction == null || actualLegalActions == null || position == null)
            throw new IllegalArgumentException("Canonical action, legal actions and position are required");
        for (MCTSTreeTimeBudget.Action actual : actualLegalActions) {
            if (canonicalAction.sameAs(toCanonicalAction(actual, position))) return actual;
        }
        throw new IllegalStateException("Canonical action has no legal equivalent in the current orientation: " + canonicalAction);
    }

    private static long occupancy(PylosBoard board, int symmetry) {
        long bits = 0L;
        for (int i = 0; i < LOCATION_COUNT; i++) {
            int[] c = COORDINATES[symmetry][i];
            PylosSphere sphere = board.getBoardLocation(c[0], c[1], c[2]).getSphere();
            int value = sphere == null ? 0
                    : sphere.PLAYER_COLOR == PylosPlayerColor.LIGHT ? 1 : 2;
            bits = (bits << 2) | value;
        }
        return bits;
    }

    private static int[][][] createCoordinateSequences() {
        return new int[][][] {
                // Symmetry 0
                {
                        {0,0,0}, {0,1,0}, {0,2,0}, {0,3,0},
                        {1,0,0}, {1,1,0}, {1,2,0}, {1,3,0},
                        {2,0,0}, {2,1,0}, {2,2,0}, {2,3,0},
                        {3,0,0}, {3,1,0}, {3,2,0}, {3,3,0},

                        {0,0,1}, {0,1,1}, {0,2,1},
                        {1,0,1}, {1,1,1}, {1,2,1},
                        {2,0,1}, {2,1,1}, {2,2,1},

                        {0,0,2}, {0,1,2},
                        {1,0,2}, {1,1,2},

                        {0,0,3}
                },

                // Symmetry 1
                {
                        {0,0,0}, {1,0,0}, {2,0,0}, {3,0,0},
                        {0,1,0}, {1,1,0}, {2,1,0}, {3,1,0},
                        {0,2,0}, {1,2,0}, {2,2,0}, {3,2,0},
                        {0,3,0}, {1,3,0}, {2,3,0}, {3,3,0},

                        {0,0,1}, {1,0,1}, {2,0,1},
                        {0,1,1}, {1,1,1}, {2,1,1},
                        {0,2,1}, {1,2,1}, {2,2,1},

                        {0,0,2}, {1,0,2},
                        {0,1,2}, {1,1,2},

                        {0,0,3}
                },

                // Symmetry 2
                {
                        {3,0,0}, {3,1,0}, {3,2,0}, {3,3,0},
                        {2,0,0}, {2,1,0}, {2,2,0}, {2,3,0},
                        {1,0,0}, {1,1,0}, {1,2,0}, {1,3,0},
                        {0,0,0}, {0,1,0}, {0,2,0}, {0,3,0},

                        {2,0,1}, {2,1,1}, {2,2,1},
                        {1,0,1}, {1,1,1}, {1,2,1},
                        {0,0,1}, {0,1,1}, {0,2,1},

                        {1,0,2}, {1,1,2},
                        {0,0,2}, {0,1,2},

                        {0,0,3}
                },

                // Symmetry 3
                {
                        {3,0,0}, {2,0,0}, {1,0,0}, {0,0,0},
                        {3,1,0}, {2,1,0}, {1,1,0}, {0,1,0},
                        {3,2,0}, {2,2,0}, {1,2,0}, {0,2,0},
                        {3,3,0}, {2,3,0}, {1,3,0}, {0,3,0},

                        {2,0,1}, {1,0,1}, {0,0,1},
                        {2,1,1}, {1,1,1}, {0,1,1},
                        {2,2,1}, {1,2,1}, {0,2,1},

                        {1,0,2}, {0,0,2},
                        {1,1,2}, {0,1,2},

                        {0,0,3}
                },

                // Symmetry 4
                {
                        {3,3,0}, {3,2,0}, {3,1,0}, {3,0,0},
                        {2,3,0}, {2,2,0}, {2,1,0}, {2,0,0},
                        {1,3,0}, {1,2,0}, {1,1,0}, {1,0,0},
                        {0,3,0}, {0,2,0}, {0,1,0}, {0,0,0},

                        {2,2,1}, {2,1,1}, {2,0,1},
                        {1,2,1}, {1,1,1}, {1,0,1},
                        {0,2,1}, {0,1,1}, {0,0,1},

                        {1,1,2}, {1,0,2},
                        {0,1,2}, {0,0,2},

                        {0,0,3}
                },

                // Symmetry 5
                {
                        {3,3,0}, {2,3,0}, {1,3,0}, {0,3,0},
                        {3,2,0}, {2,2,0}, {1,2,0}, {0,2,0},
                        {3,1,0}, {2,1,0}, {1,1,0}, {0,1,0},
                        {3,0,0}, {2,0,0}, {1,0,0}, {0,0,0},

                        {2,2,1}, {1,2,1}, {0,2,1},
                        {2,1,1}, {1,1,1}, {0,1,1},
                        {2,0,1}, {1,0,1}, {0,0,1},

                        {1,1,2}, {0,1,2},
                        {1,0,2}, {0,0,2},

                        {0,0,3}
                },

                // Symmetry 6
                {
                        {0,3,0}, {0,2,0}, {0,1,0}, {0,0,0},
                        {1,3,0}, {1,2,0}, {1,1,0}, {1,0,0},
                        {2,3,0}, {2,2,0}, {2,1,0}, {2,0,0},
                        {3,3,0}, {3,2,0}, {3,1,0}, {3,0,0},

                        {0,2,1}, {0,1,1}, {0,0,1},
                        {1,2,1}, {1,1,1}, {1,0,1},
                        {2,2,1}, {2,1,1}, {2,0,1},

                        {0,1,2}, {0,0,2},
                        {1,1,2}, {1,0,2},

                        {0,0,3}
                },

                // Symmetry 7
                {
                        {0,3,0}, {1,3,0}, {2,3,0}, {3,3,0},
                        {0,2,0}, {1,2,0}, {2,2,0}, {3,2,0},
                        {0,1,0}, {1,1,0}, {2,1,0}, {3,1,0},
                        {0,0,0}, {1,0,0}, {2,0,0}, {3,0,0},

                        {0,2,1}, {1,2,1}, {2,2,1},
                        {0,1,1}, {1,1,1}, {2,1,1},
                        {0,0,1}, {1,0,1}, {2,0,1},

                        {0,1,2}, {1,1,2},
                        {0,0,2}, {1,0,2},

                        {0,0,3}
                }
        };
    }
}