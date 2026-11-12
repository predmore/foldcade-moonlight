package com.limelight.binding.input;

/**
 * Foldcade: whether a controller's input may reach the PC, and one lock for that decision and
 * every packet sent under it. Input arrives on several threads (main, the USB driver's, the
 * sensors'), so a check of a flag before sending is not enough: a report could pass the check,
 * then send after suspension had already sent the PC a controller at rest. Here suspending waits
 * for any send in progress, sends the neutral state, and only then lets nothing more through.
 */
final class InputSuspension {
    private boolean suspended;

    synchronized boolean isSuspended() {
        return suspended;
    }

    /** Sends unless suspended, never overlapping a change of state. False when it was dropped. */
    synchronized boolean emit(Runnable send) {
        if (suspended) {
            return false;
        }
        send.run();
        return true;
    }

    /**
     * Runs a state update that may send ([emit] inside it is allowed), so a suspension cannot
     * clear state half-way through it.
     */
    synchronized void locked(Runnable update) {
        update.run();
    }

    /**
     * Suspends or resumes. Suspending runs [neutral] first, still unsuspended so its own packets
     * go out, and no other send can come between it and the suspension. False when unchanged.
     */
    synchronized boolean set(boolean suspend, Runnable neutral) {
        if (suspend == suspended) {
            return false;
        }
        if (suspend) {
            neutral.run();
        }
        suspended = suspend;
        return true;
    }
}
