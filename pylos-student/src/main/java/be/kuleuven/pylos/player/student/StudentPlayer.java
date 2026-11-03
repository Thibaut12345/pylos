package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.player.PylosPlayerObserver;

import java.util.*;
import java.util.function.Supplier;

/** MCTS player with adversarial UCT selection, tactically biased rollouts and symmetry-aware states. */
public class StudentPlayer extends PylosPlayer {
    private static final int NUMBER_OF_SIMULATIONS = 1500;
    private static final double WIN_SCORE = 1.0, DRAW_SCORE = 0.0, LOSS_SCORE = -1.0;
    private static final int MAX_ROLLOUT_ACTIONS = 256;
    private final Random searchRandom = new Random();

    private PylosGameSimulator simulator;
    private PylosBoard board;
    private PylosLocation[] usableLocationBuffer;
    private MCTSTree lightTree;
    private MCTSTree darkTree;
    private MCTSTree tree;
    private MCTSTree.Action chosenTreeAction;
    //private int decisionsMade;
    //private boolean setDecisionsMade;

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
//        if(!setDecisionsMade){
//            setDecisionsMade = true;
//            decisionsMade = 0;
//        }
        this.board = board;
        if (usableLocationBuffer == null || usableLocationBuffer.length != board.getLocations().length)
            usableLocationBuffer = new PylosLocation[board.getLocations().length];
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
        return 0L;
    }

    private MCTSTree.Action searchBestAction() {
        MCTSTree.Node root = tree.getRoot();
        for (int i = 0; i < NUMBER_OF_SIMULATIONS; i++) runOneIteration(root);
        chosenTreeAction = tree.bestRootAction();
        if (chosenTreeAction == null) return null;
        PylosSymmetry.Position position = canonicalPosition();
        //decisionsMade++;
        return PylosSymmetry.toActualAction(chosenTreeAction, board, simulator.getColor(), position);
    }

    private void runOneIteration(MCTSTree.Node root) {
        List<MCTSTree.Node> path = new ArrayList<>();
        List<UndoRecord> undo = new ArrayList<>();
        MCTSTree.Node node = root;
        path.add(node);
        PylosGameState startState = simulator.getState();
        PylosPlayerColor startColor = simulator.getColor();
        long before = stateKey();
        double reward;
        try {
            int depth = 0;
            while (!isTerminal()) {
                PylosSymmetry.Position position = canonicalPosition();
                MCTSTree.Node child = tree.selectOrExpand(
                        node,
                        () -> canonicalActions(legalActions(), position)
                );
                MCTSTree.Action actualAction = PylosSymmetry.toActualAction(
                        child.getAction(), board, simulator.getColor(), position
                );
                undo.add(apply(actualAction));
                tree.observeState(child, stateKey(), simulator.getColor());
                node = child;
                path.add(node);
                if (node.getVisits() == 0 || ++depth >= MAX_ROLLOUT_ACTIONS) break;
            }
            reward = rollout(undo);
        } finally {
            undoAll(undo);
            long after = stateKey();
            if (before !=after) throw new IllegalStateException("Board restoration failed: " + before + " != " + after);
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


    private MCTSTree.Action chooseRolloutAction(List<MCTSTree.Action> available) {
        PylosPlayerColor mover = simulator.getColor();
        List<MCTSTree.Action> squareMoves = new ArrayList<>(), safe = new ArrayList<>();
        MCTSTree.Action winning = null;

        // Square detection is a board-geometry check. Avoid changing the simulator
        // for every rollout candidate merely to discover whether it grants removals.
        boolean lastReserve = board.getReservesSize(mover) == 1;
        for (MCTSTree.Action action : available) {
            boolean createsSquare = isSquareCompletedBy(action, mover);
            if (createsSquare) {
                squareMoves.add(action);
                safe.add(action);
                continue;
            }

            // Only a placement using the player's final reserve can terminate the
            // game by exhausting that reserve. Other actions cannot cause this win check.
            if (lastReserve && action.type == MCTSTree.ActionType.PLACEMENT) {
                UndoRecord record = apply(action);
                PylosGameState state = simulator.getState();
                PylosPlayerColor winner = simulator.getWinner();
                if (state == PylosGameState.COMPLETED && winner == mover) winning = action;
                if (state != PylosGameState.COMPLETED || winner == null || winner == mover) safe.add(action);
                undo(record);
                simulator = new PylosGameSimulator(record.state, record.color, board);
                if (winning != null) break;
            } else {
                safe.add(action);
            }
        }

        if (winning != null) return winning;
        if (!squareMoves.isEmpty() && searchRandom.nextDouble() < 0.75)
            return squareMoves.get(searchRandom.nextInt(squareMoves.size()));
        List<MCTSTree.Action> choices = safe.isEmpty() ? available : safe;
        return choices.get(searchRandom.nextInt(choices.size()));
    }


    private boolean isSquareCompletedBy(MCTSTree.Action action, PylosPlayerColor mover) {
        if (action.type != MCTSTree.ActionType.PLACEMENT
                && action.type != MCTSTree.ActionType.MOVE) return false;

        PylosLocation destination = action.location;
        PylosLocation origin = action.type == MCTSTree.ActionType.MOVE
                ? action.sphere.getLocation() : null;

        for (PylosSquare square : destination.getSquares()) {
            int sameColorCount = 0;
            for (PylosLocation location : square.getLocations()) {
                if (location == destination || location == origin) continue;
                PylosSphere occupied = location.getSphere();
                if (occupied != null && occupied.PLAYER_COLOR == mover) sameColorCount++;
            }
            if (sameColorCount == 3) return true;
        }
        return false;
    }

    private List<MCTSTree.Action> legalActions() {
        PylosGameState state = simulator.getState();
        PylosPlayerColor color = simulator.getColor();
        List<MCTSTree.Action> actions = new ArrayList<>(40);

        if (state == PylosGameState.MOVE) {
            PylosLocation[] locations = board.getLocations();
            int usableCount = 0;

            // Collect usable destinations once instead of testing all 30 locations
            // separately for every movable sphere.
            for (PylosLocation location : locations) {
                if (location.isUsable()) usableLocationBuffer[usableCount++] = location;
            }

            for (PylosSphere sphere : board.getSpheres(color)) {
                if (sphere.isReserve() || !sphere.canMove()) continue;

                PylosLocation from = sphere.getLocation();
                for (int i = 0; i < usableCount; i++) {
                    PylosLocation destination = usableLocationBuffer[i];
                    // Equivalent to canMoveTo after filtering usable destinations:
                    // the destination must be higher and not supported by this sphere.
                    if (from.Z < destination.Z && !from.isBelow(destination))
                        actions.add(MCTSTree.Action.move(sphere, destination));
                }
            }

            // A player can still move an existing sphere after using the final reserve.
            if (board.getReservesSize(color) > 0) {
                PylosSphere reserve = board.getReserve(color);
                for (int i = 0; i < usableCount; i++)
                    actions.add(MCTSTree.Action.placement(reserve, usableLocationBuffer[i]));
            }
        } else if (state == PylosGameState.REMOVE_FIRST) {
            for (PylosSphere sphere : board.getSpheres(color))
                if (!sphere.isReserve() && sphere.canRemove())
                    actions.add(MCTSTree.Action.removeFirst(sphere));
        } else if (state == PylosGameState.REMOVE_SECOND) {
            for (PylosSphere sphere : board.getSpheres(color))
                if (!sphere.isReserve() && sphere.canRemove())
                    actions.add(MCTSTree.Action.removeSecond(sphere));
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

final class MCTSTree {
    public static final double DEFAULT_EXPLORATION_CONSTANT = 1;
    public enum ActionType { PLACEMENT, MOVE, REMOVE_FIRST, REMOVE_SECOND, PASS }


    public static final class Action {
        public final ActionType type;
        public final PylosSphere sphere;
        public final PylosLocation location;
        private final boolean canonical;
        final int fromIndex;
        final int toIndex;

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
        private final Action action;
        private final List<Node> children = new ArrayList<>();
        private final BitSet expandedActionIds = new BitSet();
        private int expandedActionCount;
        private int visits;
        private double totalReward;
        private long stateKey;
        private boolean observed;
        private PylosPlayerColor playerToMove;

        private Node(Action action, long key, PylosPlayerColor player) {
            this.action = action; stateKey = key; playerToMove = player; observed = (player != null);
        }
        public Action getAction() { return action; }
        public int getVisits() { return visits; }
        public PylosPlayerColor getColor() { return playerToMove; }
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


    public MCTSTree(PylosPlayerColor rewardPerspective, long key, PylosPlayerColor playerToMove, Random random) {
        this(rewardPerspective, key, playerToMove, random, DEFAULT_EXPLORATION_CONSTANT);
    }

    public MCTSTree(PylosPlayerColor rewardPerspective, long key, PylosPlayerColor playerToMove, Random random, double explorationConstant) {
        if (rewardPerspective == null || playerToMove == null || random == null)
            throw new IllegalArgumentException("Reward perspective, root player, key and RNG are required");
        if (explorationConstant < 0.0 || Double.isNaN(explorationConstant))
            throw new IllegalArgumentException("Invalid exploration constant");
        this.random = random;
        this.explorationConstant = explorationConstant;
        this.rewardPerspective = rewardPerspective;
        persistentRoot = new Node(null, key, playerToMove);
        root = persistentRoot;
        indexState(persistentRoot);
    }

    public Node getRoot() { return root; }

    public void ensureRoot(PylosPlayerColor player, long key) {
        if (player == null) throw new IllegalArgumentException("Root player and key are required");
        if (player == root.playerToMove && key == root.stateKey) return;
        Node match = stateIndex.get(key);
        if (match == null) {
            match = new Node( null, key, player);
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

    public Node selectOrExpand(Node node, Supplier<List<Action>> legalActionsSupplier) {
        if (node == null || legalActionsSupplier == null)
            throw new IllegalArgumentException("Node and legal-action supplier are required");
        List<Action> legal = legalActionsSupplier.get();
        if (legal == null || legal.isEmpty())
            throw new IllegalArgumentException("A non-empty legal-action list is required");

        // Keep only compact action identifiers in the node. The temporary legal-action
        // list can be discarded after finding and marking one action as expanded.
        for (int i = legal.size() - 1; i >= 0; i--) {
            Action action = legal.get(i);
            int actionId = actionId(action);
            if (!node.expandedActionIds.get(actionId)) {
                node.expandedActionIds.set(actionId);
                node.expandedActionCount++;
                Node child = new Node(action, 0L, null);
                node.children.add(child);
                return child;
            }
        }
        Node best = null; double bestValue = Double.NEGATIVE_INFINITY;
        for (Node child : node.children) {
            if (child.visits == 0) return child;
            double exploitation = child.totalReward / child.visits;
            if (node.playerToMove != rewardPerspective) exploitation = -exploitation;
            double exploration = explorationConstant * Math.sqrt(Math.log(Math.max(1, node.visits)) / child.visits);
            double value = exploitation + exploration;
            if (value > bestValue) { bestValue = value; best = child; }
        }
        if (best == null) throw new IllegalStateException("No child available for UCT selection");
        return best;
    }

    private static int actionId(Action action) {
        // Each action type has a disjoint 31 x 31 range. Index -1 is encoded as 0.
        int from = action.fromIndex + 1;
        int to = action.toIndex + 1;
        return action.type.ordinal() * 31 * 31 + from * 31 + to;
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

final class PylosSymmetry {
    private static final int LOCATION_COUNT = 30;
    private static final int SYMMETRY_COUNT = 8;
    private static final int[][][] COORDINATES = createCoordinateSequences();

    private PylosSymmetry() { }

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

        private PylosLocation locationAt(PylosBoard board, int index) {
            if (board == null || index < 0 || index >= LOCATION_COUNT)
                throw new IllegalArgumentException("Board and valid canonical location index are required");
            int[] coordinates = COORDINATES[symmetry][index];
            return board.getBoardLocation(coordinates[0], coordinates[1], coordinates[2]);
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

        // PylosBoard maintains this packed 2-bit-per-location representation
        // incrementally, so read it once instead of dereferencing 30 spheres for
        // each of the eight board symmetries.
        long boardBits = board.toLong();
        int bestSymmetry = 0;
        long bestOccupancy = occupancy(boardBits, 0);
        for (int symmetry = 1; symmetry < SYMMETRY_COUNT; symmetry++) {
            long candidate = occupancy(boardBits, symmetry);
            if (Long.compareUnsigned(candidate, bestOccupancy) < 0) {
                bestOccupancy = candidate;
                bestSymmetry = symmetry;
            }
        }
        long key = bestOccupancy | ((long) state.ordinal() << 60);
        if (playerToMove == PylosPlayerColor.DARK) key |= Long.MIN_VALUE;
        return new Position(key, bestSymmetry);
    }

    public static MCTSTree.Action toCanonicalAction(MCTSTree.Action action, Position position) {
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
        return MCTSTree.Action.canonical(action.type, from, to);
    }

    public static MCTSTree.Action toActualAction(
            MCTSTree.Action canonicalAction,
            PylosBoard board,
            PylosPlayerColor playerColor,
            Position position
    ) {
        if (canonicalAction == null || board == null || playerColor == null || position == null)
            throw new IllegalArgumentException("Canonical action, board, player color and position are required");

        PylosLocation from = canonicalAction.fromIndex < 0
                ? null : position.locationAt(board, canonicalAction.fromIndex);
        PylosLocation to = canonicalAction.toIndex < 0
                ? null : position.locationAt(board, canonicalAction.toIndex);

        switch (canonicalAction.type) {
            case PLACEMENT:
                PylosSphere reserve = board.getReserve(playerColor);
                if (reserve == null || to == null)
                    throw new IllegalStateException("Canonical placement cannot be mapped to the current board");
                return MCTSTree.Action.placement(reserve, to);
            case MOVE:
                PylosSphere movingSphere = from == null ? null : from.getSphere();
                if (movingSphere == null || to == null)
                    throw new IllegalStateException("Canonical move cannot be mapped to the current board");
                return MCTSTree.Action.move(movingSphere, to);
            case REMOVE_FIRST:
                PylosSphere firstRemoval = from == null ? null : from.getSphere();
                if (firstRemoval == null)
                    throw new IllegalStateException("Canonical first removal cannot be mapped to the current board");
                return MCTSTree.Action.removeFirst(firstRemoval);
            case REMOVE_SECOND:
                PylosSphere secondRemoval = from == null ? null : from.getSphere();
                if (secondRemoval == null)
                    throw new IllegalStateException("Canonical second removal cannot be mapped to the current board");
                return MCTSTree.Action.removeSecond(secondRemoval);
            case PASS:
                return MCTSTree.Action.pass();
            default:
                throw new IllegalStateException("Unsupported canonical action type: " + canonicalAction.type);
        }
    }

    private static long occupancy(long boardBits, int symmetry) {
        long bits = 0L;
        int[] sourceOffsets = SOURCE_BIT_OFFSETS[symmetry];
        for (int i = 0; i < LOCATION_COUNT; i++) {
            long value = (boardBits >>> sourceOffsets[i]) & 3L;
            bits = (bits << 2) | value;
        }
        return bits;
    }

    // PylosBoard packs locations in z, x, y order, with two bits per location.
    // Precompute the source bit offset for each location in each canonical ordering.
    private static final int[][] SOURCE_BIT_OFFSETS = createSourceBitOffsets();

    private static int[][] createSourceBitOffsets() {
        int[][] offsets = new int[SYMMETRY_COUNT][LOCATION_COUNT];
        for (int symmetry = 0; symmetry < SYMMETRY_COUNT; symmetry++) {
            for (int i = 0; i < LOCATION_COUNT; i++) {
                int[] c = COORDINATES[symmetry][i];
                int z = c[2];
                int precedingLocations = 0;
                for (int level = 0; level < z; level++)
                    precedingLocations += (4 - level) * (4 - level);
                int boardIndex = precedingLocations + c[0] * (4 - z) + c[1];
                offsets[symmetry][i] = boardIndex * 2;
            }
        }
        return offsets;
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
