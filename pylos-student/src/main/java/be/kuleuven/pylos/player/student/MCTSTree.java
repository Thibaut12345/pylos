package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.game.PylosSphere;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;

public final class MCTSTree {
    public static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(2.0);
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
        if (node == null || legal == null || legal.isEmpty()) throw new IllegalArgumentException("Node and legal actions are required");
        if (node.unexpandedActions == null) {
            node.unexpandedActions = new ArrayList<>(legal);
            Collections.shuffle(node.unexpandedActions, random);
        }
        if (!node.unexpandedActions.isEmpty()) {
            Node child = new Node(node, node.unexpandedActions.remove(node.unexpandedActions.size() - 1), 0L, null);
            node.children.add(child); return child;
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
