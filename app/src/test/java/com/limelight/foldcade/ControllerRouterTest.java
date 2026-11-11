package com.limelight.foldcade;

import org.junit.Before;
import org.junit.Test;
import java.util.List;
import static com.limelight.foldcade.ControllerRouter.Route.*;
import static org.junit.Assert.*;

public class ControllerRouterTest {
    private final ControllerRouter router = new ControllerRouter();
    private static final long BACK = ControllerRouter.keyId(9, 4), A = ControllerRouter.keyId(9, 96), X = ControllerRouter.keyId(9, 99);

    @Before public void companionAttached() { router.setCompanionAvailable(true); }

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

    @Test public void keysTheGameHeldAtAHandoverAreReleasedOnceAndTheirRepeatsSwallowed() {
        assertEquals(GAME, router.key(X, true, 0, false));
        router.setCompanionOwns(true);
        List<Long> released = router.releaseHeldByGame();
        assertEquals(List.of(X), released);
        assertEquals(0, router.heldByGame());
        // Repeats never re-press it on the PC, and never reach the companion half-way through.
        assertEquals(DROP, router.key(X, true, 1, false));
        assertEquals(DROP, router.key(X, true, 7, false));
        // Its physical release is swallowed too: the game already had its release.
        assertEquals(DROP, router.key(X, false, 0, false));
        // After that it is an ordinary key for whoever owns the controller.
        assertEquals(COMPANION, router.key(X, true, 0, false));
        assertEquals(COMPANION, router.key(X, false, 0, false));
        assertTrue(router.releaseHeldByGame().isEmpty());
    }

    @Test public void aFreshPressOfAReleasedKeyStartsOver() {
        router.key(X, true, 0, false);
        router.setCompanionOwns(true);
        router.releaseHeldByGame();
        // The release went missing (focus moved), then the key is pressed again.
        assertEquals(COMPANION, router.key(X, true, 0, false));
        assertEquals(COMPANION, router.key(X, true, 1, false));
        assertEquals(COMPANION, router.key(X, false, 0, false));
    }

    @Test public void companionKeysAreNotReleasedForTheGame() {
        router.setCompanionOwns(true);
        router.key(A, true, 0, false);
        assertTrue(router.releaseHeldByGame().isEmpty());
        assertEquals(COMPANION, router.key(A, false, 0, false));
    }

    @Test public void withNoCompanionTheOwnershipKeyIsAnOrdinaryKeyAndOwnershipIsRefused() {
        ControllerRouter alone = new ControllerRouter();
        assertFalse(alone.setCompanionOwns(true));
        assertFalse(alone.companionOwns());
        assertEquals(GAME, alone.key(BACK, true, 0, true));
        assertEquals(GAME, alone.key(BACK, true, 1, true));
        assertEquals(GAME, alone.key(BACK, false, 0, true));
        assertFalse(alone.companionOwns());
        // Handing the controller back always works.
        assertTrue(alone.setCompanionOwns(false));
    }

    @Test public void losingTheCompanionHandsTheControllerBack() {
        assertTrue(router.setCompanionOwns(true));
        router.setCompanionAvailable(false);
        assertFalse(router.companionOwns());
        assertFalse(router.setCompanionOwns(true));
        assertEquals(GAME, router.key(A, true, 0, false));
    }

    @Test public void anOwnershipKeyPressedWhileTheCompanionLeavesStillComesUpSwallowed() {
        assertEquals(TOGGLE, router.key(BACK, true, 0, true));
        router.setCompanionAvailable(false);
        assertEquals(DROP, router.key(BACK, true, 2, true));
        assertEquals(DROP, router.key(BACK, false, 0, true));
    }
}
