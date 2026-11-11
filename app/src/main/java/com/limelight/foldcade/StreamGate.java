package com.limelight.foldcade;

import java.io.IOException;

/**
 * Android-independent: which stream may still talk to the PC through Foldcade's broker.
 *
 * Every stream activity gets a transport bound to the generation current when it was made.
 * Restarting the stream or quitting the game advances the generation, so a stopped stream's
 * startup that is still running (waiting on {@code serverinfo}, say) can never go on to send
 * {@code launch} or {@code resume}: that would replace the session key the new stream
 * negotiated, or start a game the player just quit. A request already on its way when the
 * generation moves on is waited for ({@link #drain}) before the next stream opens or the quit
 * goes out.
 */
public final class StreamGate {
    public interface Requests { String request(String path, String query) throws IOException; }

    /** A request a stale stream tried to make; the stream that made it has been replaced. */
    public static final class StaleException extends IOException {
        public final String path;
        StaleException(String path) {
            super("This stream was replaced.");
            this.path = path;
        }
    }

    public interface Refusals { void refused(int generation, String path); }

    private int generation;
    /** Requests on their way, by the generation that made them. */
    private final java.util.Map<Integer, Integer> inFlight = new java.util.HashMap<>();
    private Refusals refusals;

    public synchronized int current() { return generation; }

    public synchronized void onRefusal(Refusals refusals) { this.refusals = refusals; }

    /** Makes every transport handed out so far stale. Returns the new generation. */
    public synchronized int advance() { return ++generation; }

    /** A transport that sends through [requests] for as long as its generation is current. */
    public com.limelight.nvstream.http.NvHTTP.RequestTransport transport(Requests requests) {
        final int mine = current();
        return (path, query) -> {
            begin(mine, path);
            try {
                return requests.request(path, query);
            } finally {
                finish(mine);
            }
        };
    }

    private synchronized void begin(int mine, String path) throws StaleException {
        if (mine != generation) {
            if (refusals != null) refusals.refused(mine, path);
            throw new StaleException(path);
        }
        inFlight.merge(mine, 1, Integer::sum);
    }

    private synchronized void finish(int mine) {
        Integer left = inFlight.get(mine);
        if (left == null || left <= 1) inFlight.remove(mine); else inFlight.put(mine, left - 1);
        notifyAll();
    }

    private boolean staleInFlight() {
        for (int g : inFlight.keySet()) if (g != generation) return true;
        return false;
    }

    /**
     * Waits until no request from an older generation is still on its way, for at most [timeoutMs].
     * True when the way is clear. A launch can take a minute, so callers allow for that.
     */
    public synchronized boolean drain(long timeoutMs) throws InterruptedException {
        long until = System.currentTimeMillis() + timeoutMs;
        while (staleInFlight()) {
            long left = until - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }

    synchronized int inFlight() {
        int all = 0;
        for (int n : inFlight.values()) all += n;
        return all;
    }
}
