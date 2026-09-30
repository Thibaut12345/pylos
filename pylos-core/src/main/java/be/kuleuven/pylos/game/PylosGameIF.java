package be.kuleuven.pylos.game;

import be.kuleuven.pylos.player.PylosPlayer;

/**
 * Created by Jan on 20/02/2015.
 */
public interface PylosGameIF {

    /**
     * returns the current state of the game
     *
     * @return PylosGameState
     * @see PylosGameState
     */
    PylosGameState getState();

    /**
     * returns true if we have a winner
     *
     * @return
     */
    boolean isFinished();

    /**
     * returns the winner of the game if the game is finished, null otherwise
     *
     * @return
     */
    PylosPlayer getWinner();

    /**
     * returns the number of spheres when the game was finished
     *
     * @return
     */
    int getReserveSizeOfWinner();

    /**
     * move a reserve (or used) sphere to any (higher) usable location
     * this method can only be called if the game is in MOVE state
     * thus call this method in the PylosPlayer.doMove(..) method
     *
     * @param pylosSphere
     * @param toLocation
     */
    void moveSphere(PylosSphere pylosSphere, PylosLocation toLocation);

    /**
     * remove a used sphere
     * this method can only be called if the game is in REMOVE_FIRST or REMOVE_SECOND state
     * thus call this method in the PylosPlayer.doRemove(..) or PylosPlayer.doRemoveOrPass(..) method
     *
     * @param pylosSphere
     */
    void removeSphere(PylosSphere pylosSphere);

    /**
     * remove a used sphere
     * this method can only be called if the game is in  REMOVE_SECOND state
     * thus call this method in the PylosPlayer.doRemoveOrPass(..) method
     */
    void pass();

    /**
     * returns true if moving this sphere will result in a draw state
     *
     * @param pylosSphere
     * @param toLocation
     * @return
     */
    boolean moveSphereIsDraw(PylosSphere pylosSphere, PylosLocation toLocation);

    /**
     * returns true if removing this sphere will result in a draw state
     *
     * @param pylosSphere
     * @return
     */
    boolean removeSphereIsDraw(PylosSphere pylosSphere);

    /**
     * returns true if passing will result in a draw state
     *
     * @return
     */
    boolean passIsDraw();

}
