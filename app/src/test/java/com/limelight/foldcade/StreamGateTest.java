package com.limelight.foldcade;

import com.limelight.nvstream.http.NvHTTP;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class StreamGateTest {
    private final StreamGate gate = new StreamGate();
    private final List<String> sent = new ArrayList<>();

    private NvHTTP.RequestTransport transport() {
        return gate.transport((path, query) -> { sent.add(path); return "<root status_code=\"200\"/>"; });
    }

    @Test public void aCurrentStreamTalksToThePc() throws Exception {
        NvHTTP.RequestTransport mine = transport();
        mine.request("serverinfo", "");
        mine.request("launch", "appid=1");
        assertEquals(List.of("serverinfo", "launch"), sent);
        assertEquals(0, gate.inFlight());
    }

    @Test public void aReplacedStreamCanNeverLaunchOrResume() throws Exception {
        NvHTTP.RequestTransport old = transport();
        old.request("serverinfo", "");
        List<String> refused = new ArrayList<>();
        gate.onRefusal((generation, path) -> refused.add(generation + ":" + path));
        int now = gate.advance();
        NvHTTP.RequestTransport replacement = transport();
        try {
            old.request("launch", "appid=1");
            fail("a stale launch went out");
        } catch (StreamGate.StaleException e) {
            assertEquals("launch", e.path);
        }
        try {
            old.request("resume", "");
            fail("a stale resume went out");
        } catch (StreamGate.StaleException expected) { }
        replacement.request("resume", "");
        assertEquals(List.of("serverinfo", "resume"), sent);
        assertEquals(List.of((now - 1) + ":launch", (now - 1) + ":resume"), refused);
    }

    @Test public void drainWaitsForAStaleRequestAlreadyOnItsWay() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        NvHTTP.RequestTransport old = gate.transport((path, query) -> {
            started.countDown();
            try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
            return "";
        });
        Thread launch = new Thread(() -> { try { old.request("launch", ""); } catch (java.io.IOException ignored) { } });
        launch.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        gate.advance();
        // Still on its way: the next stream must not open yet.
        assertFalse(gate.drain(50));
        finish.countDown();
        assertTrue(gate.drain(5000));
        launch.join(5000);
        assertEquals(0, gate.inFlight());
    }

    @Test public void drainDoesNotWaitForTheCurrentGeneration() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        gate.advance();
        NvHTTP.RequestTransport current = gate.transport((path, query) -> {
            started.countDown();
            try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { throw new java.io.IOException(e); }
            return "";
        });
        Thread request = new Thread(() -> { try { current.request("serverinfo", ""); } catch (java.io.IOException ignored) { } });
        request.start();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertTrue(gate.drain(0));
        assertEquals(1, gate.inFlight());
        finish.countDown();
        request.join(5000);
    }

    @Test public void aFailedRequestStillLeavesTheGateClear() {
        NvHTTP.RequestTransport failing = gate.transport((path, query) -> { throw new java.io.IOException("offline"); });
        try { failing.request("serverinfo", ""); fail(); } catch (java.io.IOException expected) { }
        assertEquals(0, gate.inFlight());
    }
}
