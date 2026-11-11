package com.limelight.foldcade;

import java.util.HashSet;
import java.util.Set;

/**
 * Android-independent: who gets each key, the game on the PC or the companion's own controls.
 *
 * The game owns the controller until the player presses the ownership key (the Thor's Back
 * button, or Ctrl+Alt+Shift+M). That key never reaches either side. A key goes back up to
 * whichever side saw it go down, so changing owners never leaves a button held on the PC or
 * a hold running in the companion.
 */
public final class ControllerRouter {
    public enum Route { GAME, COMPANION, TOGGLE, DROP }

    private boolean companionOwns;
    private final Set<Long> toGame = new HashSet<>();
    private final Set<Long> toCompanion = new HashSet<>();
    private final Set<Long> swallowed = new HashSet<>();

    public static long keyId(int deviceId, int keyCode) { return ((long) deviceId << 32) | (keyCode & 0xffffffffL); }

    public boolean companionOwns() { return companionOwns; }

    public void setCompanionOwns(boolean owns) { companionOwns = owns; }

    /** Routes one key event. Repeats follow the key's first press; only a first press can toggle. */
    public Route key(long id, boolean down, int repeatCount, boolean ownershipKey) {
        if (down) {
            if (swallowed.contains(id)) return Route.DROP;
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
        if (toGame.remove(id)) return Route.GAME;
        if (toCompanion.remove(id)) return Route.COMPANION;
        if (ownershipKey) return Route.DROP;
        return companionOwns ? Route.COMPANION : Route.GAME;
    }

    /** Keys still down on the PC, for tests and diagnostics. */
    int heldByGame() { return toGame.size(); }
}
