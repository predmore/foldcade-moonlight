package com.limelight.foldcade;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Android-independent: who gets each key, the game on the PC or the companion's own controls.
 *
 * The game owns the controller until the player presses the ownership key (the Thor's Back
 * button, or Ctrl+Alt+Shift+M). That key never reaches either side, and it only counts while a
 * companion is there to take the controller ({@link #setCompanionAvailable}); with none, it is
 * an ordinary key.
 *
 * A key goes back up to whichever side saw it go down. When the companion takes the controller,
 * every key the game is holding is handed back for release ({@link #releaseHeldByGame}); its
 * repeats and its eventual physical release are then swallowed, so nothing re-presses it on the
 * PC and nothing reaches the companion half-way through a press.
 */
public final class ControllerRouter {
    public enum Route { GAME, COMPANION, TOGGLE, DROP }

    private boolean companionOwns;
    private boolean companionAvailable;
    private final Set<Long> toGame = new HashSet<>();
    private final Set<Long> toCompanion = new HashSet<>();
    private final Set<Long> swallowed = new HashSet<>();
    /** Keys the game was holding at a handover: released for it, swallowed until they come up. */
    private final Set<Long> released = new HashSet<>();

    public static long keyId(int deviceId, int keyCode) { return ((long) deviceId << 32) | (keyCode & 0xffffffffL); }

    public boolean companionOwns() { return companionOwns; }

    /** Whether a companion is attached and can take the controller. Losing it hands the controller back. */
    public void setCompanionAvailable(boolean available) {
        companionAvailable = available;
        if (!available) companionOwns = false;
    }

    public boolean companionAvailable() { return companionAvailable; }

    /** Moves the controller; refused (false) when the companion should take it but none is there. */
    public boolean setCompanionOwns(boolean owns) {
        if (owns && !companionAvailable) return false;
        companionOwns = owns;
        return true;
    }

    /**
     * The keys the game holds now, which the caller releases on the PC. From here on each is
     * swallowed, repeats included, until it physically comes up.
     */
    public List<Long> releaseHeldByGame() {
        List<Long> held = new ArrayList<>(toGame);
        released.addAll(toGame);
        toGame.clear();
        return held;
    }

    /** Routes one key event. Repeats follow the key's first press; only a first press can toggle. */
    public Route key(long id, boolean down, int repeatCount, boolean ownershipKey) {
        // With no companion to take the controller, the ownership key is just a key for the game.
        if (ownershipKey && !companionAvailable && !swallowed.contains(id)) ownershipKey = false;
        if (down) {
            if (swallowed.contains(id)) return Route.DROP;
            if (released.contains(id)) {
                if (repeatCount > 0) return Route.DROP;
                // A fresh press means its release went missing (focus moved); start over with it.
                released.remove(id);
            }
            if (repeatCount > 0) {
                if (toGame.contains(id)) return Route.GAME;
                if (toCompanion.contains(id)) return Route.COMPANION;
                // A key already held before this session saw it: never let it toggle.
                if (ownershipKey) return Route.DROP;
            }
            if (ownershipKey) {
                swallowed.add(id);
                return Route.TOGGLE;
            }
            if (companionOwns) {
                toCompanion.add(id);
                return Route.COMPANION;
            }
            toGame.add(id);
            return Route.GAME;
        }
        if (swallowed.remove(id)) return Route.DROP;
        if (released.remove(id)) return Route.DROP;
        if (toGame.remove(id)) return Route.GAME;
        if (toCompanion.remove(id)) return Route.COMPANION;
        if (ownershipKey) return Route.DROP;
        return companionOwns ? Route.COMPANION : Route.GAME;
    }

    /** Keys still down on the PC, for tests and diagnostics. */
    int heldByGame() { return toGame.size(); }
}
