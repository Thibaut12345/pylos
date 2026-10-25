package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.game.PylosSphere;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** UCT tree; all rewards are measured from the fixed root player's perspective. */
public final class MCTSTree {
    public static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(2.0);

    public PylosPlayerColor getRewardPerspective() {
        return rewardPerspective;
    }

    public enum ActionType { PLACEMENT, MOVE, REMOVE_FIRST, REMOVE_SECOND, PASS }

    public static final class Action {
        public final ActionType type;
        public final PylosSphere sphere;
        public final PylosLocation location;
        private Action(ActionType type, PylosSphere sphere, PylosLocation location) {
            this.type = type; this.sphere = sphere; this.location = location;
        }
        public static Action placement(PylosSphere s, PylosLocation l) { return new Action(ActionType.PLACEMENT, s, l); }
        public static Action move(PylosSphere s, PylosLocation l) { return new Action(ActionType.MOVE, s, l); }
        public static Action removeFirst(PylosSphere s) { return new Action(ActionType.REMOVE_FIRST, s, null); }
        public static Action removeSecond(PylosSphere s) { return new Action(ActionType.REMOVE_SECOND, s, null); }
        public static Action pass() { return new Action(ActionType.PASS, null, null); }
        public boolean sameAs(Action other) {
            return other != null && type == other.type && sphere == other.sphere && location == other.location;
        }
        @Override public String toString() {
            switch (type) {
                case PLACEMENT: case MOVE: return type + "(" + sphere + " -> " + location + ")";
                case REMOVE_FIRST: case REMOVE_SECOND: return type + "(" + sphere + ")";
                default: return "PASS";
            }
        }
    }

    public static final class Node {
        private Node parent;
        private final Action action;
        private final List<Node> children = new ArrayList<>();
        private List<Action> unexpandedActions;
        private int visits;
        private double totalReward;
        private String stateKey;
        private PylosPlayerColor playerToMove;
        private Node(Node parent, Action action, String key, PylosPlayerColor player) {
            this.parent = parent; this.action = action; stateKey = key; playerToMove = player;
        }
        public Action getAction() { return action; }
        public int getVisits() { return visits; }
        public double getMeanReward() { return visits == 0 ? 0.0 : totalReward / visits; }
        public PylosPlayerColor getColor() { return playerToMove; }
        public String getStateKey() { return stateKey; }
        public void setObservedState(String key, PylosPlayerColor color) {
            if (key == null || color == null) throw new IllegalArgumentException("Observed state and player are required");
            if (stateKey != null) {
                if (!stateKey.equals(key) || playerToMove != color)
                    throw new IllegalStateException("Node state mismatch: same action produced a different state");
                return;
            }
            stateKey = key; playerToMove = color;
        }
    }

    private final Random random;
    private final double explorationConstant;
    private final PylosPlayerColor rewardPerspective;
    private Node root;

    public MCTSTree(PylosPlayerColor player, String key, Random random) {
        this(player, key, random, DEFAULT_EXPLORATION_CONSTANT);
    }
    public MCTSTree(PylosPlayerColor player, String key, Random random, double explorationConstant) {
        if (player == null || key == null || random == null) throw new IllegalArgumentException("Root player, key and RNG are required");
        if (explorationConstant < 0.0 || Double.isNaN(explorationConstant)) throw new IllegalArgumentException("Invalid exploration constant");
        this.random = random; this.explorationConstant = explorationConstant; rewardPerspective = player;
        root = new Node(null, null, key, player);
    }
    public Node getRoot() { return root; }

    public void ensureRoot(PylosPlayerColor player, String key) {
        if (player == null || key == null) throw new IllegalArgumentException("Root player and key are required");
        if (player == root.playerToMove && key.equals(root.stateKey)) return;
        Node match = findState(root, player, key);
        root = match == null ? new Node(null, null, key, player) : copyAsRoot(match, key, player);
    }
    private Node findState(Node node, PylosPlayerColor player, String key) {
        if (node == null) return null;
        if (player == node.playerToMove && key.equals(node.stateKey)) return node;
        for (Node child : node.children) { Node found = findState(child, player, key); if (found != null) return found; }
        return null;
    }

    public Node selectOrExpand(Node node, List<Action> legal) {
        if (node == null || legal == null || legal.isEmpty()) throw new IllegalArgumentException("Node and legal actions are required");
        if (node.unexpandedActions == null) {
            node.unexpandedActions = new ArrayList<>(legal);
            Collections.shuffle(node.unexpandedActions, random);
        }
        if (!node.unexpandedActions.isEmpty()) {
            Node child = new Node(node, node.unexpandedActions.remove(node.unexpandedActions.size() - 1), null, null);
            node.children.add(child); return child;
        }
        Node best = null; double bestValue = Double.NEGATIVE_INFINITY;
        for (Node child : node.children) {
            if (child.visits == 0) return child;
            double exploitation = child.totalReward / child.visits;
            // The opponent chooses actions that minimize the root player's reward.
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

    /** Most-visited root action wins; estimated value breaks visit-count ties. */
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

    public void advanceRoot(Action action) {
        if (action == null) throw new IllegalArgumentException("Action cannot be null");
        Node selected = null;
        for (Node child : root.children) if (child.action != null && child.action.sameAs(action)) { selected = child; break; }
        if (selected == null) throw new IllegalStateException("Selected action is not a child of the current root");
        if (selected.stateKey == null || selected.playerToMove == null) throw new IllegalStateException("Selected child has no observed state");
        root = copyAsRoot(selected, selected.stateKey, selected.playerToMove);
    }

    private Node copyAsRoot(Node old, String key, PylosPlayerColor player) {
        Node copy = new Node(null, null, key, player);
        copy.visits = old.visits; copy.totalReward = old.totalReward; copy.unexpandedActions = old.unexpandedActions;
        copy.children.addAll(old.children);
        for (Node child : copy.children) child.parent = copy;
        return copy;
    }
}
