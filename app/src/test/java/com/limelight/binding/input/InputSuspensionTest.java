package com.limelight.binding.input;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public class InputSuspensionTest {
    private final InputSuspension suspension = new InputSuspension();
    private final List<String> sent = Collections.synchronizedList(new ArrayList<>());

    @Test public void nothingIsSentWhileSuspendedAndSendingResumesAfter() {
        assertTrue(suspension.emit(() -> sent.add("a")));
        assertTrue(suspension.set(true, () -> sent.add("neutral")));
        assertFalse(suspension.emit(() -> sent.add("b")));
        assertFalse(suspension.set(true, () -> sent.add("neutral again")));
        assertTrue(suspension.set(false, () -> sent.add("never")));
        assertTrue(suspension.emit(() -> sent.add("c")));
        assertEquals(List.of("a", "neutral", "c"), sent);
    }

    @Test public void theNeutralStateItselfGoesOut() {
        // Suspending sends the controller at rest through the same path, before it closes.
        suspension.set(true, () -> assertTrue(suspension.emit(() -> sent.add("neutral"))));
        assertEquals(List.of("neutral"), sent);
    }

    @Test public void aReportAlreadySendingOnAnotherThreadLandsBeforeTheNeutralState() throws Exception {
        CountDownLatch inside = new CountDownLatch(1), finish = new CountDownLatch(1);
        // The USB driver's thread has passed the check and is sending a pressed button.
        Thread usb = new Thread(() -> suspension.locked(() -> {
            inside.countDown();
            try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            suspension.emit(() -> sent.add("pressed"));
        }));
        usb.start();
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        AtomicBoolean done = new AtomicBoolean();
        Thread main = new Thread(() -> { suspension.set(true, () -> sent.add("neutral")); done.set(true); });
        main.start();
        main.join(100);
        assertFalse("suspension must wait for the report in progress", done.get());
        finish.countDown();
        main.join(5000);
        usb.join(5000);
        assertEquals(List.of("pressed", "neutral"), sent);
    }

    @Test public void underContentionNothingFollowsTheNeutralState() throws Exception {
        for (int round = 0; round < 200; round++) {
            InputSuspension gate = new InputSuspension();
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch running = new CountDownLatch(1);
            Thread usb = new Thread(() -> {
                running.countDown();
                while (!stop.get()) gate.locked(() -> gate.emit(() -> log.add("report")));
            });
            usb.start();
            assertTrue(running.await(5, TimeUnit.SECONDS));
            gate.set(true, () -> log.add("neutral"));
            Thread.sleep(1);
            stop.set(true);
            usb.join(5000);
            int neutral = log.indexOf("neutral");
            assertTrue(neutral >= 0);
            assertEquals("round " + round, log.size() - 1, neutral);
        }
    }
}
