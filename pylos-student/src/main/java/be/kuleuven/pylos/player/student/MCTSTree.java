package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosPlayerColor;
import be.kuleuven.pylos.game.PylosSphere;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Monte Carlo Tree Search tree for Pylos.
 *
 * Rewards are always stored from the root player's perspective: win = +1,
 * draw = 0, loss = -1. During UCT selection, the exploitation term is
 * negated at nodes where the opponent is to move.
 */
public final class MCTSTree {
    public static final double DEFAULT_EXPLORATION_CONSTANT = Math.sqrt(2.0);

    public enum ActionType {
        PLACEMENT,
        MOVE,
        REMOVE_FIRST,
        REMOVE_SECOND,
        PASS
    }

    /** References are safe here because this Pylos implementation keeps its sphere/location objects stable. */
    public static final class Action {
        public final ActionType type;
        public final PylosSphere sphere;
        public final PylosLocation location;

        private Action(ActionType type, PylosSphere sphere, PylosLocation location) {
            this.type = type;
            this.sphere = sphere;
            this.location = location;
        }

        public static Action placement(PylosSphere sphere, PylosLocation location) {
            return new Action(ActionType.PLACEMENT, sphere, location);
        }

        public static Action move(PylosSphere sphere, PylosLocation location) {
            return new Action(ActionType.MOVE, sphere, location);
        }

        public static Action removeFirst(PylosSphere sphere) {
            return new Action(ActionType.REMOVE_FIRST, sphere, null);
        }

        public static Action removeSecond(PylosSphere sphere) {
            return new Action(ActionType.REMOVE_SECOND, sphere, null);
        }

        public static Action pass() {
            return new Action(ActionType.PASS, null, null);
        }

        public boolean sameAs(Action other) {
            return other != null
                    && type == other.type
                    && sphere == other.sphere
                    && location == other.location;
        }

        @Override
        public String toString() {
            switch (type) {
                case PLACEMENT:
                case MOVE:
                    return type + "(" + sphere + " -> " + location + ")";
                case REMOVE_FIRST:
                case REMOVE_SECOND:
                    return type + "(" + sphere + ")";
                default:
                    return "PASS";
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

        private Node(Node parent, Action action, String stateKey, PylosPlayerColor playerToMove) {
            this.parent = parent;
            this.action = action;
            this.stateKey = stateKey;
            this.playerToMove = playerToMove;
        }

        public Action getAction() { return action; }
        public int getVisits() { return visits; }
        public double getMeanReward() { return visits == 0 ? 0.0 : totalReward / visits; }

        public void setObservedState(String key, PylosPlayerColor color) {
            if (key == null) {
                throw new IllegalArgumentException("Node stateKey cannot be null.");
            }

            if (color == null) {
                throw new IllegalArgumentException("Node playerToMove cannot be null.");
            }

            // Once a node has an observed state, it must remain consistent.
            if (stateKey != null) {
                if (!stateKey.equals(key) || playerToMove != color) {
                    throw new IllegalStateException(
                            "Node state mismatch. The same action produced a different state."
                    );
                }

                return;
            }

            stateKey = key;
            playerToMove = color;
        }

        public PylosPlayerColor getColor() {
            return playerToMove;
        }

        public String getStateKey() {
            return stateKey;
        }
    }

    private final Random random;
    private final double explorationConstant;
    private Node root;
    private final PylosPlayerColor rewardPerspective;

    public MCTSTree(PylosPlayerColor rootPlayer, String rootStateKey, Random random) {
        this(rootPlayer, rootStateKey, random, DEFAULT_EXPLORATION_CONSTANT);
    }

    public MCTSTree(PylosPlayerColor rootPlayer, String rootStateKey, Random random, double explorationConstant) {
        if (rootPlayer == null || rootStateKey == null || random == null) {
            throw new IllegalArgumentException("Root player, state key and random generator are required");
        }
        if (explorationConstant < 0.0) {
            throw new IllegalArgumentException("Exploration constant cannot be negative");
        }
        this.random = random;
        this.explorationConstant = explorationConstant;
        this.rewardPerspective = rootPlayer;
        this.root = new Node(null, null, rootStateKey, rootPlayer);
    }

    public Node getRoot() { return root; }

    public void ensureRoot(PylosPlayerColor playerToMove, String stateKey) {
        if (root != null && playerToMove == root.playerToMove && stateKey.equals(root.stateKey)) {
            return;
        }
        Node matching = findState(root, playerToMove, stateKey);
        if (matching != null) {
            root = copyAsRoot(matching, stateKey, playerToMove);
        } else {
            root = new Node(null, null, stateKey, playerToMove);
        }
    }

    private Node findState(Node node, PylosPlayerColor playerToMove, String stateKey) {
        if (node == null) {
            return null;
        }
        if (playerToMove == node.playerToMove && stateKey.equals(node.stateKey)) {
            return node;
        }
        for (Node child : node.children) {
            Node found = findState(child, playerToMove, stateKey);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public Node selectOrExpand(Node node, List<Action> legalActions) {
        if (node == null || legalActions == null || legalActions.isEmpty()) {
            throw new IllegalArgumentException("A node and at least one legal action are required");
        }

        if (node.unexpandedActions == null) {
            node.unexpandedActions = new ArrayList<>(legalActions);
            Collections.shuffle(node.unexpandedActions, random);
        }

        if (!node.unexpandedActions.isEmpty()) {
            Action action = node.unexpandedActions.remove(node.unexpandedActions.size() - 1);
            Node child = new Node(node, action, null, null);
            node.children.add(child);
            return child;
        }

        Node best = null;
        double bestValue = Double.NEGATIVE_INFINITY;

        for (Node child : node.children) {
            if (child.visits == 0) {
                // Always explore an unvisited child before comparing UCT scores.
                return child;
            }

            double meanReward = child.totalReward / child.visits;

            // Rewards are stored from the AI's fixed perspective.
            // The opponent should minimize the AI's expected reward.
            if (node.playerToMove != rewardPerspective) {
                meanReward = -meanReward;
            }

            double exploration = explorationConstant
                    * Math.sqrt(
                    Math.log(Math.max(1, node.visits))
                            / child.visits
            );

            double uct = meanReward + exploration;

            if (uct > bestValue) {
                bestValue = uct;
                best = child;
            }
        }

        if (best == null) {
            throw new IllegalStateException(
                    "No child available for UCT selection"
            );
        }

        return best;
    }

    /** Adds one rollout result to every node on the selected path. */
    public void backpropagate(List<Node> path, double rootPerspectiveReward) {
        if (path == null) {
            throw new IllegalArgumentException("Path is required");
        }
        for (Node node : path) {
            node.visits++;
            node.totalReward += rootPerspectiveReward;
        }
    }

    /** Selects the root action with the most visits, breaking ties by root-perspective value. */
    public Action bestRootAction() {
        if (root.children.isEmpty()) {
            return null;
        }

        Node bestChild = null;
        double bestMeanReward = Double.NEGATIVE_INFINITY;

        for (Node child : root.children) {
            if (bestChild == null || child.visits > bestChild.visits) {
                bestChild = child;
                bestMeanReward = getMeanReward(child);
            } else if (child.visits == bestChild.visits) {
                double meanReward = getMeanReward(child);

                if (meanReward > bestMeanReward) {
                    bestChild = child;
                    bestMeanReward = meanReward;
                }
            }
        }

        return bestChild.action;
    }

    private double getMeanReward(Node node) {
        if (node.visits == 0) {
            return Double.NEGATIVE_INFINITY;
        }

        return node.totalReward / node.visits;
    }

    /**
     * Advances the root after the real game accepts an action. The chosen
     * child and all its descendants are retained whenever possible.
     */
    public void advanceRoot(Action actualAction) {
        if (actualAction == null) {
            throw new IllegalArgumentException("Action cannot be null.");
        }

        Node selectedChild = null;

        for (Node child : root.children) {
            if (child.action != null && child.action.sameAs(actualAction)) {
                selectedChild = child;
                break;
            }
        }

        if (selectedChild == null) {
            throw new IllegalStateException(
                    "Cannot advance root: the selected action is not a child of the current root."
            );
        }

        if (selectedChild.stateKey == null || selectedChild.playerToMove == null) {
            throw new IllegalStateException(
                    "Cannot advance root: the selected child has no recorded state."
            );
        }

        root = copyAsRoot(
                selectedChild,
                selectedChild.stateKey,
                selectedChild.playerToMove
        );
    }


    private Node copyAsRoot(Node old, String stateKey, PylosPlayerColor playerToMove) {
        Node newRoot = new Node(null, null, stateKey, playerToMove);
        newRoot.visits = old.visits;
        newRoot.totalReward = old.totalReward;
        newRoot.unexpandedActions = old.unexpandedActions;
        newRoot.children.addAll(old.children);
        for (Node child : newRoot.children) {
            child.parent = newRoot;
        }
        return newRoot;
    }
}
