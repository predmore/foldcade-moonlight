package com.limelight.foldcade;

/** Android-independent relative touchpad state machine. Coordinates are density-independent pixels. */
public final class TouchpadGesture {
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
    private boolean dragging, active, moved, lifted;

    public TouchpadGesture(Output output, float slop) {
        this.output = output;
        this.slop = slop;
    }

    public void down(float x, float y, long time) {
        cancel();
        this.x = originX = x;
        this.y = originY = y;
        started = lastMove = time;
        maxPointers = 1;
        active = true;
        if (time - lastTap <= 300 && Math.hypot(x - tapX, y - tapY) <= slop * 3) {
            dragging = true;
            output.button(false, true);
        }
        lastTap = -1000;
    }

    public void pointers(int count, float x, float y, boolean pointerLifted) {
        if (!active) return;
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
        if (!moved && !dragging) return;
        if (count == 2) {
            scrollX += -dx * 4;
            scrollY += -dy * 4;
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
        } else if (!moved && maxPointers <= 2 && time - started <= 250) {
            boolean right = maxPointers == 2;
            output.button(right, true);
            output.button(right, false);
            if (!right) {
                lastTap = time;
                tapX = x;
                tapY = y;
            }
        }
        active = false;
    }

    public void cancel() {
        if (dragging) output.button(false, false);
        active = dragging = moved = lifted = false;
        travel = remainderX = remainderY = scrollX = scrollY = 0;
    }

    public static float acceleration(float dx, float dy, long elapsedMs) {
        float speed = (float)Math.hypot(dx, dy) / Math.max(1, elapsedMs);
        return 1.2f + Math.min(2.8f, speed * 0.65f);
    }

    private static short delta(float value) { return (short)Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value)); }
}
