package com.limelight.foldcade;

import org.junit.Test;
import static com.limelight.foldcade.ControllerRouter.Route.*;
import static org.junit.Assert.*;

public class ControllerRouterTest {
    private final ControllerRouter router = new ControllerRouter();
    private static final long BACK = ControllerRouter.keyId(9, 4), A = ControllerRouter.keyId(9, 96), X = ControllerRouter.keyId(9, 99);

    @Test public void gameOwnsUntilTheOwnershipKeyAndThatKeyReachesNeitherSide() {
        assertEquals(GAME, router.key(A, true, 0, false));
        assertEquals(GAME, router.key(A, false, 0, false));
        assertEquals(TOGGLE, router.key(BACK, true, 0, true));
        assertEquals(DROP, router.key(BACK, true, 1, true));
        assertEquals(DROP, router.key(BACK, false, 0, true));
        router.setCompanionOwns(true);
        assertEquals(COMPANION, router.key(A, true, 0, false));
        assertEquals(COMPANION, router.key(A, false, 0, false));
    }

    @Test public void aKeyHeldAcrossTheSwitchComesUpWhereItWentDown() {
        assertEquals(GAME, router.key(X, true, 0, false));
        router.key(BACK, true, 0, true);
        router.setCompanionOwns(true);
        router.key(BACK, false, 0, true);
        // X is still held on the PC: its repeats and its release go to the game, never to the companion.
        assertEquals(GAME, router.key(X, true, 3, false));
        assertEquals(GAME, router.key(X, false, 0, false));
        assertEquals(0, router.heldByGame());
        // And a companion press that is still held when the game takes over finishes in the companion.
        assertEquals(COMPANION, router.key(A, true, 0, false));
        router.setCompanionOwns(false);
        assertEquals(COMPANION, router.key(A, false, 0, false));
        assertEquals(GAME, router.key(A, true, 0, false));
    }

    @Test public void anOwnershipKeyAlreadyHeldNeverToggles() {
        assertEquals(DROP, router.key(BACK, true, 5, true));
        assertEquals(DROP, router.key(BACK, false, 0, true));
        assertFalse(router.companionOwns());
    }

    @Test public void keysAreTrackedPerDevice() {
        long other = ControllerRouter.keyId(12, 96);
        assertEquals(GAME, router.key(A, true, 0, false));
        router.setCompanionOwns(true);
        assertEquals(COMPANION, router.key(other, true, 0, false));
        assertEquals(GAME, router.key(A, false, 0, false));
        assertEquals(COMPANION, router.key(other, false, 0, false));
    }
}
