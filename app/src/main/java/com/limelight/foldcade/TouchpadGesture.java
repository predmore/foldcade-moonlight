package com.limelight.foldcade;

/**
 * Android-independent relative touchpad state machine. Coordinates are density-independent pixels.
 *
 * A left tap is held for {@link #DOUBLE_TAP_MS} so a second touch can turn it into a drag without
 * the PC seeing a double-click first. The owner calls {@link #tapTimeout()} once that window passes.
 * A quick second tap still sends a double-click.
 */
public final class TouchpadGesture {
    public static final long DOUBLE_TAP_MS = 300;

    public interface Output {
        void move(short x, short y);
        void button(boolean right, boolean down);
        void scroll(short vertical, short horizontal);
    }

    private final Output output;
    private final float slop;
    private float x, y, originX, originY, travel, remainderX, remainderY, scrollX, scrollY;
    private float tapX, tapY;
    private long started, lastMove, lastTap = -1000;
    private int maxPointers;
    // pendingTap: a left tap not yet sent. armed: a second touch on that tap, not yet a drag or a tap.
    private boolean dragging, active, moved, lifted, pendingTap, armed;

    public TouchpadGesture(Output output, float slop) {
        this.output = output;
        this.slop = slop;
    }

    public void down(float x, float y, long time) {
        cancel();
        boolean second = pendingTap && time - lastTap <= DOUBLE_TAP_MS && Math.hypot(x - tapX, y - tapY) <= slop * 3;
        if (pendingTap && !second) click(false);
        pendingTap = false;
        armed = second;
        this.x = originX = x;
        this.y = originY = y;
        started = lastMove = time;
        maxPointers = 1;
        active = true;
        lastTap = -1000;
    }

    public void pointers(int count, float x, float y, boolean pointerLifted) {
        if (!active) return;
        if (armed) {
            // A second finger on the second touch: the first tap was a plain click.
            armed = false;
            click(false);
        }
        maxPointers = Math.max(maxPointers, count);
        lifted |= pointerLifted;
        this.x = x;
        this.y = y;
        // Adding a second finger changes the centroid, but isn't cursor movement.
        originX = x;
        originY = y;
        if (dragging) {
            output.button(false, false);
            dragging = false;
            moved = true;
        }
    }

    public void move(float x, float y, int count, long time) {
        if (!active || lifted || maxPointers > 2) return;
        float dx = x - this.x, dy = y - this.y;
        this.x = x;
        this.y = y;
        travel = Math.max(travel, (float)Math.hypot(x - originX, y - originY));
        moved |= travel > slop;
        float gain = acceleration(dx, dy, time - lastMove);
        lastMove = time;
        if (armed && moved) {
            armed = false;
            dragging = true;
            output.button(false, true);
        }
        if (!moved && !dragging) return;
        if (count == 2) {
            // Natural scrolling on both axes: the page follows the fingers.
            scrollX += -dx * 4;
            scrollY += dy * 4;
            short sx = delta(scrollX), sy = delta(scrollY);
            scrollX -= sx;
            scrollY -= sy;
            output.scroll(sy, sx);
        } else if (maxPointers == 1) {
            remainderX += dx * gain;
            remainderY += dy * gain;
            short mx = delta(remainderX), my = delta(remainderY);
            remainderX -= mx;
            remainderY -= my;
            output.move(mx, my);
        }
    }

    public void up(long time) {
        if (!active) return;
        if (dragging) {
            output.button(false, false);
            dragging = false;
        } else if (armed) {
            // Two quick taps: a double-click.
            armed = false;
            click(false);
            click(false);
        } else if (!moved && maxPointers <= 2 && time - started <= 250) {
            if (maxPointers == 2) {
                click(true);
            } else {
                pendingTap = true;
                lastTap = time;
                tapX = x;
                tapY = y;
            }
        }
        active = false;
    }

    /** True when a left tap waits for {@link #tapTimeout()}. */
    public boolean hasPendingTap() { return pendingTap; }

    /** Sends a held left tap once no second touch came. */
    public void tapTimeout() {
        if (!pendingTap) return;
        pendingTap = false;
        click(false);
    }

    public void cancel() {
        if (dragging) output.button(false, false);
        active = dragging = moved = lifted = armed = false;
        travel = remainderX = remainderY = scrollX = scrollY = 0;
    }

    private void click(boolean right) {
        output.button(right, true);
        output.button(right, false);
    }

    public static float acceleration(float dx, float dy, long elapsedMs) {
        float speed = (float)Math.hypot(dx, dy) / Math.max(1, elapsedMs);
        return 1.2f + Math.min(2.8f, speed * 0.65f);
    }

    private static short delta(float value) { return (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)); }
}
