package com.limelight.foldcade;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class TouchpadGestureTest {
    private final List<String> events = new ArrayList<>();
    private final TouchpadGesture pad = new TouchpadGesture(new TouchpadGesture.Output() {
        public void move(short x, short y) { events.add("move:" + x + ":" + y); }
        public void button(boolean right, boolean down) { events.add((right ? "right:" : "left:") + down); }
        public void scroll(short vertical, short horizontal) { events.add("scroll:" + vertical + ":" + horizontal); }
    }, 8);

    @Test public void tapAndTwoFingerTapClickCorrectButtons() {
        pad.down(10, 10, 0);
        pad.up(80);
        assertTrue(events.isEmpty());
        assertTrue(pad.hasPendingTap());
        pad.tapTimeout();
        assertEquals(List.of("left:true", "left:false"), events);
        events.clear();
        pad.down(10, 10, 1000);
        pad.pointers(2, 30, 30, false);
        pad.pointers(2, 30, 30, true);
        pad.up(1100);
        assertEquals(List.of("right:true", "right:false"), events);
    }

    @Test public void cursorAcceleratesAndMovementDoesNotClick() {
        assertTrue(TouchpadGesture.acceleration(100, 0, 10) > TouchpadGesture.acceleration(1, 0, 10));
        assertEquals(4f, TouchpadGesture.acceleration(1000, 0, 0), 0.01f);
        pad.down(0, 0, 0);
        pad.move(20, 0, 1, 20);
        pad.up(80);
        assertEquals(1, events.size());
        assertTrue(events.get(0).startsWith("move:"));
    }

    @Test public void twoFingerDragScrollsWithoutClickOrReleaseJump() {
        pad.down(0, 0, 0);
        pad.pointers(2, 10, 0, false);
        pad.move(10, 20, 2, 30);
        pad.pointers(2, 10, 20, true);
        pad.move(100, 100, 1, 60);
        pad.up(80);
        assertEquals(List.of("scroll:80:0"), events);
    }

    @Test public void doubleTapHoldDragsAndCancelReleases() {
        pad.down(0, 0, 0);
        pad.up(50);
        pad.down(0, 0, 150);
        pad.move(20, 0, 1, 180);
        pad.cancel();
        pad.up(300);
        assertEquals("left:true", events.get(0));
        assertTrue(events.get(1).startsWith("move:"));
        assertEquals("left:false", events.get(2));
        assertEquals(3, events.size());
    }

    @Test public void thirdFingerAndLongPressDoNotClick() {
        pad.down(0, 0, 0);
        pad.pointers(3, 10, 10, false);
        pad.up(50);
        pad.down(0, 0, 1000);
        pad.up(1500);
        assertTrue(events.isEmpty());
    }

    @Test public void quickDoubleTapDoubleClicks() {
        pad.down(0, 0, 0);
        pad.up(50);
        pad.down(0, 0, 150);
        pad.up(200);
        pad.tapTimeout();
        assertEquals(List.of("left:true", "left:false", "left:true", "left:false"), events);
    }

    @Test public void lateSecondTapSendsFirstClickFirst() {
        pad.down(0, 0, 0);
        pad.up(50);
        pad.down(0, 0, 1000);
        assertEquals(List.of("left:true", "left:false"), events);
    }

    @Test public void horizontalScrollFollowsFingers() {
        pad.down(0, 0, 0);
        pad.pointers(2, 0, 0, false);
        pad.move(20, 0, 2, 30);
        assertEquals(List.of("scroll:0:-80"), events);
    }

    @Test public void naturalScrollOffTurnsTwoFingerScrollAround() {
        pad.setNaturalScroll(false);
        pad.down(0, 0, 0);
        pad.pointers(2, 0, 0, false);
        pad.move(0, 20, 2, 30);
        assertEquals(List.of("scroll:-80:0"), events);
    }

    @Test public void pointerSpeedScalesMovement() {
        pad.down(0, 0, 0);
        pad.move(20, 0, 1, 100);
        int normal = Integer.parseInt(events.get(0).split(":")[1]);
        events.clear();
        pad.setSpeed(2f);
        pad.down(0, 0, 1000);
        pad.move(20, 0, 1, 1100);
        int fast = Integer.parseInt(events.get(0).split(":")[1]);
        assertTrue(fast >= normal * 2 - 1);
    }

    @Test public void stripSlidesLikeAWheelAndIgnoresNaturalScroll() {
        pad.setNaturalScroll(true);
        pad.stripDown(50, 200, 0);
        pad.stripMove(60);
        pad.stripMove(70);
        pad.stripUp(400);
        // Down the strip is down the page; the slop's 10 dp are not lost once it moves.
        assertEquals(List.of("scroll:-60:0", "scroll:-60:0"), events);
    }

    @Test public void stripTapScrollsOneNotchByHalf() {
        pad.stripDown(20, 200, 0);
        pad.stripUp(100);
        pad.stripDown(180, 200, 1000);
        pad.stripUp(1100);
        assertEquals(List.of("scroll:120:0", "scroll:-120:0"), events);
    }

    @Test public void slowStripPressIsNotATap() {
        pad.stripDown(20, 200, 0);
        pad.stripUp(600);
        pad.stripDown(20, 200, 1000);
        pad.stripCancel();
        pad.stripUp(1050);
        assertTrue(events.isEmpty());
    }
}
