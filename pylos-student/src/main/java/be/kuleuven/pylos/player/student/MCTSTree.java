package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.game.PylosSphere;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Random;
import java.util.function.Supplier;

public final class MCTSTree {
    public static final double DEFAULT_EXPLORATION_CONSTANT = 0.75;
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
