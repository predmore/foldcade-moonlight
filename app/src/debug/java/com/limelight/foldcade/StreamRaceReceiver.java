package com.limelight.foldcade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Debug APK only: drives the restart and quit races on a real session, faster than a hand can.
 * {@code adb shell am broadcast -n <debug package>/com.limelight.foldcade.StreamRaceReceiver --es race X}
 * where X is {@code restart-twice} (restart again the moment the replacement stream exists, while
 * its startup is still talking to the PC; {@code --ei delay N} waits N ms more) or {@code restart-then-quit} (Quit while the old
 * stream is still stopping).
 */
public final class StreamRaceReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        FoldcadeSession session = FoldcadeSession.current();
        String race = intent.getStringExtra("race");
        if (session == null || session.ended || race == null) {
            Log.w("Foldcade", "Race: no session");
            return;
        }
        Log.i("Foldcade", "Race: " + race);
        session.restartStream();
        if ("restart-then-quit".equals(race)) {
            session.quitGame();
        } else if ("restart-twice".equals(race)) {
            int delay = intent.getIntExtra("delay", 0);
            Handler main = new Handler(Looper.getMainLooper());
            main.post(new Runnable() {
                @Override public void run() {
                    if (session.ended) return;
                    // The replacement attached (restarting cleared): restart before it connects.
                    if (session.restarting) { main.postDelayed(this, 5); return; }
                    main.postDelayed(() -> {
                        Log.i("Foldcade", "Race: restarting again while generation " + session.gateGeneration() + " starts");
                        session.restartStream();
                    }, delay);
                }
            });
        }
    }
}
