package com.limelight.foldcade;

import android.app.ActivityOptions;
import android.view.KeyEvent;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;

import com.limelight.Game;
import com.limelight.nvstream.http.ComputerDetails;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** The only exported release plugin entry point. */
public final class FoldcadePlayActivity extends androidx.activity.ComponentActivity {
    private FoldcadeSession pendingSession;
    private boolean failed;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            if (Build.VERSION.SDK_INT < 26) throw new IllegalArgumentException("Foldcade plugins require Android 8 or later.");
            Map<String, Object> values = new HashMap<>();
            Bundle extras = getIntent().getExtras();
            if (extras != null) for (String key : extras.keySet()) values.put(key, extras.get(key));
            PluginProtocol.PlayRequest request = new PluginProtocol.PlayRequest(values);
            DisplayManager displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (displays.getDisplay(request.topDisplay) == null) throw new IllegalArgumentException("The top screen is not available. Try again from Foldcade.");
            BrokerClient broker = new BrokerClient(this, request.authority, request.hostUuid, request.launchToken);
            new Thread(() -> {
                try {
                    broker.hello();
                    ComputerDetails host = broker.host();
                    Appearance appearance = broker.appearance();
                    runOnUiThread(() -> start(request, host, broker, appearance));
                } catch (IOException | RuntimeException e) {
                    runOnUiThread(() -> fail(e instanceof IOException ? e.getMessage() : PluginProtocol.userMessage("internal")));
                }
            }, "Foldcade-plugin-connect").start();
        } catch (IOException | RuntimeException e) {
            fail(e.getMessage());
        }
    }

    private void start(PluginProtocol.PlayRequest request, ComputerDetails host, BrokerClient broker, Appearance appearance) {
        if (isFinishing() || isDestroyed()) return;
        if (Build.VERSION.SDK_INT < 26) { finish(); return; }
        try {
            if (FoldcadeSession.current() != null) {
                fail("A stream is already open. Disconnect it before starting another game.");
                return;
            }
            pendingSession = FoldcadeSession.create(request, host, broker, appearance);
            Intent stream = new Intent(this, FoldcadeStreamActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(PluginProtocol.SESSION, pendingSession.id)
                    .putExtra(PluginProtocol.GENERATION, pendingSession.gateGeneration())
                    .putExtra(Game.EXTRA_HOST, host.activeAddress.address)
                    .putExtra(Game.EXTRA_PORT, host.activeAddress.port)
                    .putExtra(Game.EXTRA_HTTPS_PORT, host.httpsPort)
                    .putExtra(Game.EXTRA_PC_UUID, host.uuid)
                    .putExtra(Game.EXTRA_PC_NAME, host.name)
                    .putExtra(Game.EXTRA_APP_ID, request.appId)
                    .putExtra(Game.EXTRA_APP_NAME, request.appName);
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(request.topDisplay);
            startActivity(stream, options.toBundle());
            pendingSession = null;
            finish();
        } catch (RuntimeException e) {
            if (pendingSession != null) pendingSession.end();
            fail("The stream could not open on the selected display. Try again from Foldcade.");
        }
    }

    /** Shows why the play request cannot start, in the companion's style, on the screen it launched on. */
    private void fail(String message) {
        if (isFinishing() || isDestroyed() || failed) return;
        failed = true;
        com.limelight.foldcade.ui.StartError.show(this, message, this::finish);
    }

    /** The message closes with A, B, Back, Enter or Escape, from a controller or a keyboard. */
    // AndroidX inherits this public Activity callback through its restricted core shim.
    @android.annotation.SuppressLint("RestrictedApi")
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!failed) return super.dispatchKeyEvent(event);
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_BUTTON_A: case KeyEvent.KEYCODE_BUTTON_B: case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_ESCAPE: case KeyEvent.KEYCODE_DPAD_CENTER:
                if (event.getAction() == KeyEvent.ACTION_UP) finish();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
