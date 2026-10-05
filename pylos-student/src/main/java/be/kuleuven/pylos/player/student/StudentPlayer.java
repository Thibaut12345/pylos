package be.kuleuven.pylos.player.student;

import be.kuleuven.pylos.game.*;
import be.kuleuven.pylos.player.PylosPlayer;

import java.util.ArrayList;

/**
 * Created by Jan on 20/02/2015.
 */
public class StudentPlayer extends PylosPlayer {
    private static final int NUMBER_OF_SIMULATIONS = 4;

    /*
     * Score used for a random simulation.
     */
    private static final double WIN_SCORE = 1.0;
    private static final double DRAW_SCORE = 0.5;
    private static final double LOSS_SCORE = 0.0;

    private PylosGameSimulator simulator;
    private PylosBoard board;


    private double bestScore;
    private PylosSphere bestSphere;
    private PylosLocation bestLocation;

    public StudentPlayer(){}

    @Override
    public void doMove(PylosGameIF game, PylosBoard board) {
        /* board methods
         * 	PylosLocation[] allLocations = board.getLocations();
         * 	PylosSphere[] allSpheres = board.getSpheres();
         * 	PylosSphere[] mySpheres = board.getSpheres(this);
         * 	PylosSphere myReserveSphere = board.getReserve(this); */

        /* game methods
         * game.moveSphere(myReserveSphere, allLocations[0]); */

        /*
        * MinMAX versie
        *
        *
        *
        * */

        /*
        * Monte carlo versie
        *
        *   predictive model
        *   probability distribution
        *   Simulations
        *
        * */
        init(game.getState(), board);


        final PylosPlayerColor currentColor = simulator.getColor();
        PylosSphere[] spheres = board.getSpheres(currentColor);
        PylosLocation[] locations = board.getLocations();
//
        for (PylosSphere sphere : spheres) {

            if (sphere.isReserve()) {
                continue;
            }

            for (PylosLocation location : locations) {

                if (!sphere.canMoveTo(location)) {
                    continue;
                }

                PylosLocation previousLocation = sphere.getLocation();

                getObserver().checkingMoveSphere(sphere, location);
                simulator.moveSphere(sphere, location);

                double score = simulateGames();

                if (score > bestScore) {
                    bestScore = score;
                    bestSphere = sphere;
                    bestLocation = location;
                }

                simulator.undoMoveSphere(sphere, previousLocation, PylosGameState.MOVE, PLAYER_COLOR);
            }
        }
        PylosSphere reserveSphere = board.getReserve(PLAYER_COLOR);

        for (PylosLocation location : locations) {

            if (!location.isUsable()) {
                continue;
            }

            getObserver().checkingMoveSphere(reserveSphere, location);
            simulator.moveSphere(reserveSphere, location);

            double score = simulateGames();

//            double score = 10;
            if (score > bestScore) {
                bestScore = score;
                bestSphere = reserveSphere;
                bestLocation = location;
            }

            simulator.undoAddSphere(reserveSphere, PylosGameState.MOVE, currentColor);
        }
        getObserver().shout("Monte Carlo: " + (int) (bestScore * 100) + "% chance");
        game.moveSphere(bestSphere, bestLocation);
    }

    @Override
    public void doRemove(PylosGameIF game, PylosBoard board) {}


    @Override
    public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {}


    private void init(PylosGameState state, PylosBoard board) {

        this.board = board;

        this.simulator = new PylosGameSimulator(state, PLAYER_COLOR, board);

        bestScore = -1;
        bestSphere = null;
        bestLocation = null;
    }

    private double simulateGames() {

        double totalScore = 0;

        for (int i = 0; i < NUMBER_OF_SIMULATIONS; i++) {
            totalScore += randomGame();
        }

        return totalScore / NUMBER_OF_SIMULATIONS;
    }

    private double randomGame() {

        PylosGameState state = simulator.getState();

        if (state == PylosGameState.COMPLETED) {

            if (simulator.getWinner() == PLAYER_COLOR) {
                return WIN_SCORE;
            }

            return LOSS_SCORE;
        }

        if (state == PylosGameState.DRAW) {
            return DRAW_SCORE;
        }

        if (state == PylosGameState.MOVE) {
            return randomMove();
        }

        if (state == PylosGameState.REMOVE_FIRST) {
            return randomRemoveFirst();
        }

        if (state == PylosGameState.REMOVE_SECOND) {
            return randomRemoveSecond();
        }

        throw new IllegalStateException("Unknown game state: " + state);
    }

    private double randomMove() {

        PylosPlayerColor currentColor = simulator.getColor();

        ArrayList<PylosLocation> possibleLocations = new ArrayList<>();

        for (PylosLocation location : board.getLocations()) {
            if (location.isUsable()) {
                possibleLocations.add(location);
            }
        }

        if (possibleLocations.isEmpty()) {
            throw new IllegalStateException("No possible locations");
        }

        PylosSphere reserveSphere = board.getReserve(currentColor);

        int index = getRandom().nextInt(possibleLocations.size());
        PylosLocation location = possibleLocations.get(index);

        simulator.moveSphere(reserveSphere, location);
        //System.out.println("random Move 204");

        double result = randomGame();

        simulator.undoAddSphere(reserveSphere, PylosGameState.MOVE, currentColor);

        return result;
    }


    private double randomRemoveFirst() {

        PylosPlayerColor currentColor = simulator.getColor();

        ArrayList<PylosSphere> removableSpheres = new ArrayList<>();

        for (PylosSphere sphere : board.getSpheres(currentColor)) {
            if (!sphere.isReserve() && sphere.canRemove()) {
                removableSpheres.add(sphere);
            }
        }

        if (removableSpheres.isEmpty()) {
            throw new IllegalStateException("No removable sphere");
        }

        int index = getRandom().nextInt(removableSpheres.size());
        PylosSphere sphere = removableSpheres.get(index);

        PylosLocation previousLocation = sphere.getLocation();

        simulator.removeSphere(sphere);
        System.out.println("random Remove 236");

        double result = randomGame();

        simulator.undoRemoveFirstSphere(sphere, previousLocation, PylosGameState.REMOVE_FIRST, currentColor);

        return result;
    }

    private double randomRemoveSecond() {

        PylosPlayerColor currentColor = simulator.getColor();
        double result;

        ArrayList<PylosSphere> removableSpheres = new ArrayList<>();

        for (PylosSphere sphere : board.getSpheres(currentColor)) {
            if (!sphere.isReserve() && sphere.canRemove()) {
                removableSpheres.add(sphere);
            }
        }

        int numberOfActions = removableSpheres.size() + 1;

        int index = getRandom().nextInt(numberOfActions);
        if (index == removableSpheres.size()) {

            simulator.pass();
            //System.out.println("PassSecond");

            result = randomGame();

            simulator.undoPass(PylosGameState.REMOVE_SECOND, currentColor);
        } else{
            PylosSphere sphere = removableSpheres.get(index);
            PylosLocation previousLocation = sphere.getLocation();

            simulator.removeSphere(sphere);
            //System.out.println("random RemoveSecond");

            result = randomGame();

            simulator.undoRemoveSecondSphere(sphere, previousLocation,PylosGameState.REMOVE_SECOND, currentColor);
        }

        return result;
    }

}
