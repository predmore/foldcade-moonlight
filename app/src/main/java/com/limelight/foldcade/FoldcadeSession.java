package com.limelight.foldcade;

import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import java.util.UUID;

/** One in-process session; its unguessable token never grants broker access. UI methods run on main. */
public final class FoldcadeSession implements NvHTTP.RequestTransport {
    private static FoldcadeSession active;
    public final String id = UUID.randomUUID().toString();
    public final String title, pcName;
    final BrokerClient broker;
    final ComputerDetails host;
    final int bottomDisplay;
    public final boolean simulated;
    private FoldcadeStreamActivity stream;
    private CompanionActivity companion;
    private NvConnection connection;
    private boolean quitPending;
    public volatile boolean ended;
    public volatile StreamStats stats;

    private FoldcadeSession(String title, String pcName, int bottomDisplay,
                            ComputerDetails host, BrokerClient broker, boolean simulated) {
        this.title = title;
        this.pcName = pcName;
        this.bottomDisplay = bottomDisplay;
        this.host = host;
        this.broker = broker;
        this.simulated = simulated;
    }

    static FoldcadeSession create(PluginProtocol.PlayRequest request, ComputerDetails host, BrokerClient broker) {
        active = new FoldcadeSession(request.title, host.name, request.bottomDisplay, host, broker, false);
        return active;
    }

    // Called only by the debug source-set activity; no release intent can enable simulation.
    static FoldcadeSession simulated(int displayId) {
        if (active != null) throw new IllegalStateException("A session is already running");
        active = new FoldcadeSession("Companion preview", "Simulated computer", displayId, null, null, true);
        return active;
    }

    public static FoldcadeSession current() { return active; }

    @Override public String request(String path, String query) throws java.io.IOException {
        if (ended || broker == null) throw new java.io.IOException("The streaming session has ended.");
        String body = broker.request(path, query);
        if (ended) throw new java.io.IOException("The streaming session has ended.");
        return body;
    }

    static FoldcadeSession find(Intent intent) {
        FoldcadeSession session = active;
        return session != null && !session.ended && session.id.equals(intent.getStringExtra(PluginProtocol.SESSION)) ? session : null;
    }

    void attach(FoldcadeStreamActivity activity, NvConnection connection) {
        this.stream = activity;
        this.connection = connection;
        DisplayManager displays = (DisplayManager) activity.getSystemService(android.content.Context.DISPLAY_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26 && bottomDisplay >= 0 && displays.getDisplay(bottomDisplay) != null) {
            try {
                Intent intent = new Intent(activity, CompanionActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        .putExtra(PluginProtocol.SESSION, id);
                activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(bottomDisplay).toBundle());
            } catch (RuntimeException e) {
                Toast.makeText(activity, "The companion display is unavailable. Streaming on one screen.", Toast.LENGTH_LONG).show();
            }
        }
    }

    void attach(CompanionActivity activity) { companion = activity; }

    public void end() {
        if (ended) return;
        ended = true;
        if (active == this) active = null;
        FoldcadeStreamActivity oldStream = stream;
        CompanionActivity oldCompanion = companion;
        stream = null;
        companion = null;
        connection = null;
        if (oldCompanion != null) oldCompanion.finish();
        if (oldStream != null) oldStream.finish();
    }

    boolean toggleKeyboard() {
        if (companion == null) return false;
        companion.toggleKeyboard();
        return true;
    }

    void confirmDisconnect() {
        if (companion != null) companion.confirmDisconnect();
        else if (stream != null) new android.app.AlertDialog.Builder(stream)
                .setTitle(com.limelight.R.string.foldcade_disconnect_confirm)
                .setMessage(com.limelight.R.string.foldcade_disconnect_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(com.limelight.R.string.foldcade_disconnect, (dialog, which) -> end()).show();
    }

    void quitGame() {
        if (ended || quitPending) return;
        if (simulated) { end(); return; }
        quitPending = true;
        final android.app.Activity owner = companion != null ? companion : stream;
        new Thread(() -> {
            String failure = null;
            try {
                NvHTTP http = new NvHTTP(host.activeAddress, host.httpsPort, null, null, null, broker);
                if (!http.quitApp()) failure = "The computer did not quit the game.";
            } catch (Exception e) {
                failure = e instanceof PluginProtocol.BrokerException ? e.getMessage() : "The game could not be quit. You can disconnect and leave it running.";
            }
            final String message = failure;
            if (owner != null) owner.runOnUiThread(() -> {
                quitPending = false;
                if (ended) return;
                if (message == null) end();
                else if (companion != null) companion.showMessage("Unable to quit game", message);
                else Toast.makeText(owner, message, Toast.LENGTH_LONG).show();
            });
        }, "Foldcade-plugin-quit").start();
    }

    boolean key(KeyEvent event) {
        return !ended && stream != null && stream.onKey(null, event.getKeyCode(), event);
    }

    boolean motion(MotionEvent event) {
        return !ended && stream != null && stream.onGenericMotionEvent(event);
    }

    void text(String text) { if (!ended && connection != null) connection.sendUtf8Text(text); }
    void keyStroke(int code) {
        key(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        key(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
    void move(short x, short y) { if (!ended && connection != null) connection.sendMouseMove(x, y); }
    void button(byte button, boolean down) {
        if (!ended && connection != null) {
            if (down) connection.sendMouseButtonDown(button); else connection.sendMouseButtonUp(button);
        }
    }
    void scroll(short vertical, short horizontal) {
        if (!ended && connection != null) {
            if (vertical != 0) connection.sendMouseHighResScroll(vertical);
            if (horizontal != 0) connection.sendMouseHighResHScroll(horizontal);
        }
    }
}
