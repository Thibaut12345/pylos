package be.kuleuven.pylos.player.student;
import java.util.ArrayList;

import be.kuleuven.pylos.game.PylosBoard;
import be.kuleuven.pylos.game.PylosGameIF;
import be.kuleuven.pylos.player.PylosPlayer;
import be.kuleuven.pylos.game.PylosLocation;
import be.kuleuven.pylos.game.PylosSphere;

/**
 * Created by Ine on 5/05/2015.
 */
public class StudentPlayerRandomFit extends PylosPlayer {

    @Override
    public void doMove(PylosGameIF game, PylosBoard board) {

        PylosSphere sphere = board.getReserve(this);

        ArrayList<PylosLocation> possibleLocations = new ArrayList<>();

        for (PylosLocation location : board.getLocations()) {
            if (location.isUsable()) {
                possibleLocations.add(location);
            }
        }

        int randomIndex = getRandom().nextInt(possibleLocations.size());

        PylosLocation randomLocation = possibleLocations.get(randomIndex);

        game.moveSphere(sphere, randomLocation);
    }

    @Override
    public void doRemove(PylosGameIF game, PylosBoard board) {

        ArrayList<PylosSphere> removableSpheres = new ArrayList<>();

        // Zoek alle eigen bollen die verwijderd mogen worden
        for (PylosSphere sphere : board.getSpheres(this)) {
            if (sphere.canRemove()) {
                removableSpheres.add(sphere);
            }
        }

        // Kies random één van de mogelijke bollen
        int randomIndex = getRandom().nextInt(removableSpheres.size());
        PylosSphere sphereToRemove = removableSpheres.get(randomIndex);

        game.removeSphere(sphereToRemove);
    }

    @Override
    public void doRemoveOrPass(PylosGameIF game, PylosBoard board) {

        ArrayList<PylosSphere> removableSpheres = new ArrayList<>();

        // Zoek opnieuw welke bollen verwijderd mogen worden
        for (PylosSphere sphere : board.getSpheres(this)) {
            if (sphere.canRemove()) {
                removableSpheres.add(sphere);
            }
        }

        if (!removableSpheres.isEmpty()) {

            int randomIndex = getRandom().nextInt(removableSpheres.size());
            PylosSphere sphereToRemove = removableSpheres.get(randomIndex);

            game.removeSphere(sphereToRemove);

        } else {
            game.pass();
        }
    }
}
