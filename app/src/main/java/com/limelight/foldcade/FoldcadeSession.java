package com.limelight.foldcade;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.Toast;

import com.limelight.binding.input.ControllerHandler;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** One in-process session; its unguessable token never grants broker access. UI methods run on main. */
public final class FoldcadeSession implements NvHTTP.RequestTransport {
    /** How often a running stream renews Foldcade's launch token (contract §3). */
    static final long KEEP_ALIVE_MINUTES = 2;

    /** The companion's view of the session. Every call is on the main thread. */
    interface CompanionView {
        void finish();
        void toggleKeyboard();
        void confirmDisconnect();
        void showMessage(String title, String message);
        /** Quit did not close the game: offer Try again and Disconnect. [reason] is what to say. */
        void showQuitFailed(String reason);
        /** A settings restart could not open the new stream; the session ends when it is dismissed. */
        void showRestartFailed();
        void onNavKey(KeyEvent event);
        void onNavMotion(MotionEvent event);
        void onSessionChanged();
    }

    private static FoldcadeSession active;
    public final String id = UUID.randomUUID().toString();
    public final String title, pcName;
    final BrokerClient broker;
    final ComputerDetails host;
    final int topDisplay, bottomDisplay;
    public final boolean simulated;
    public final Appearance appearance;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ControllerRouter router = new ControllerRouter();
    private Intent streamIntent;
    private FoldcadeStreamActivity stream;
    private CompanionView companion;
    private NvConnection connection;
    private ScheduledExecutorService keepAlive;
    private boolean quitPending, mutedHere, simulatedMouse;
    /** Moonlight's Desktop mouse mode: the companion's pad moves the PC's cursor as positions too. */
    private boolean desktopMouse;
    /** Debug preview only: Quit fails, so its message can be seen without a real PC. */
    boolean simulateQuitFailure;
    private int[] screen = { 1920, 1080 };
    private Map<String, String> applied;
    public volatile boolean ended;
    /** A settings change is replacing the stream with a new one; the session and companion stay. */
    public volatile boolean restarting;
    public volatile StreamStats stats;
    /** The user-facing reason the broker refused or failed the last request, if it did. */
    public volatile String brokerFailure;

    private FoldcadeSession(String title, String pcName, int topDisplay, int bottomDisplay, ComputerDetails host,
                            BrokerClient broker, boolean simulated, Appearance appearance) {
        this.title = title;
        this.pcName = pcName;
        this.topDisplay = topDisplay;
        this.bottomDisplay = bottomDisplay;
        this.host = host;
        this.broker = broker;
        this.simulated = simulated;
        this.appearance = appearance != null ? appearance : Appearance.AFTERGLOW;
    }

    static FoldcadeSession create(PluginProtocol.PlayRequest request, ComputerDetails host, BrokerClient broker,
                                  Appearance appearance) {
        active = new FoldcadeSession(request.title, host.name, request.topDisplay, request.bottomDisplay, host,
                broker, false, appearance);
        return active;
    }

    // Called only by the debug source-set activity; no release intent can enable simulation.
    static FoldcadeSession simulated(int displayId) {
        if (active != null) throw new IllegalStateException("A session is already running");
        active = new FoldcadeSession("Companion preview", "Simulated PC", -1, displayId, null, null, true, null);
        return active;
    }

    public static FoldcadeSession current() { return active; }

    /** The stream's own intent, kept so a settings change can open a fresh stream for this session. */
    void setStreamIntent(Intent intent) { streamIntent = new Intent(intent); }

    @Override public String request(String path, String query) throws java.io.IOException {
        if (ended || broker == null) throw new java.io.IOException("The streaming session has ended.");
        String body;
        try {
            body = broker.request(path, query);
        } catch (PluginProtocol.BrokerException e) {
            // Upstream reports any start failure as a port problem; keep the broker's reason for the dialog.
            brokerFailure = e.getMessage();
            throw e;
        }
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
        boolean replaced = restarting;
        restarting = false;
        brokerFailure = null;
        applied = CompanionSettings.INSTANCE.restartSnapshot(PreferenceStore.of(activity));
        Boolean absolute = PreferenceStore.of(activity).bool(CompanionSettings.INSTANCE.getDesktopMouse().getKey());
        desktopMouse = absolute != null && absolute;
        screen = activity.screenSize();
        startKeepAlive();
        if (companion != null) {
            companion.onSessionChanged();
            return;
        }
        if (replaced) return;
        DisplayManager displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
        if (android.os.Build.VERSION.SDK_INT >= 26 && bottomDisplay >= 0 && displays.getDisplay(bottomDisplay) != null) {
            try {
                Intent intent = new Intent(activity, CompanionActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                        .putExtra(PluginProtocol.SESSION, id);
                activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(bottomDisplay).toBundle());
            } catch (RuntimeException e) {
                activity.showNotice(activity.getString(com.limelight.R.string.foldcade_one_screen));
            }
        }
    }

    void attach(CompanionView activity) {
        companion = activity;
        if (simulated) applied = CompanionSettings.INSTANCE.restartSnapshot(PreferenceStore.of((Context) activity));
    }

    boolean isStream(FoldcadeStreamActivity activity) { return activity == stream; }

    /** A stream activity finished. Only the current one ends the session; one replaced by a restart does not. */
    void streamFinished(FoldcadeStreamActivity activity) {
        if (activity == stream || (stream == null && !restarting)) end();
    }

    public void end() {
        if (ended) return;
        ended = true;
        if (active == this) active = null;
        FoldcadeStreamActivity oldStream = stream;
        CompanionView oldCompanion = companion;
        NvConnection oldConnection = connection;
        stream = null;
        companion = null;
        connection = null;
        if (keepAlive != null) keepAlive.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (mutedHere && oldCompanion != null) setMuted((Context) oldCompanion, false);
        // Game only stops a connection once it has reported connected. Stopping here too covers a
        // disconnect while the native start is still running. NvConnection.stop is idempotent.
        if (oldConnection != null) new Thread(oldConnection::stop, "Foldcade-plugin-stop").start();
        if (oldCompanion != null) oldCompanion.finish();
        if (oldStream != null) oldStream.finish();
    }

    /** Renews the launch token while the session lasts, so a resume after a long game still works. */
    private void startKeepAlive() {
        if (keepAlive != null || simulated || broker == null) return;
        keepAlive = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Foldcade-plugin-keepalive");
            thread.setDaemon(true);
            return thread;
        });
        keepAlive.scheduleWithFixedDelay(() -> {
            if (ended) return;
            try {
                broker.request("serverinfo", null);
            } catch (Exception ignored) {
                // A missed renewal only matters if the next one misses too; the stream itself is unaffected.
            }
        }, KEEP_ALIVE_MINUTES, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES);
    }

    // ---- The stream's settings ----

    /** Labels of settings changed since the running stream started. Empty when it is up to date. */
    java.util.List<String> pendingSettings(Context context) {
        return CompanionSettings.INSTANCE.pending(applied, CompanionSettings.INSTANCE.restartSnapshot(PreferenceStore.of(context)));
    }

    /**
     * Applies stream settings: disconnects, leaving the game running, and resumes it in a new stream
     * with the same launch token. Foldcade keeps that token alive while requests carry it.
     */
    void restartStream() {
        if (ended || restarting) return;
        if (simulated) {
            restarting = true;
            stats = null;
            notifyCompanion();
            main.postDelayed(() -> {
                if (ended) return;
                restarting = false;
                if (companion != null) applied = CompanionSettings.INSTANCE.restartSnapshot(PreferenceStore.of((Context) companion));
                notifyCompanion();
            }, 1600);
            return;
        }
        FoldcadeStreamActivity old = stream;
        NvConnection oldConnection = connection;
        if (old == null || streamIntent == null) return;
        restarting = true;
        stats = null;
        stream = null;
        connection = null;
        router.setCompanionOwns(false);
        notifyCompanion();
        old.finish();
        new Thread(() -> {
            if (oldConnection != null) oldConnection.stop();
            main.post(this::openReplacementStream);
        }, "Foldcade-plugin-restart").start();
    }

    private void openReplacementStream() {
        if (ended) return;
        Context context = companion != null ? (Context) companion : null;
        // Foldcade sessions only exist on Android 8 and later; see FoldcadePlayActivity.
        if (context == null || android.os.Build.VERSION.SDK_INT < 26) { end(); return; }
        try {
            Intent intent = new Intent(streamIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(topDisplay).toBundle());
        } catch (RuntimeException e) {
            // The game still runs on the PC. The companion says so, and the session ends when the
            // player has read it.
            restarting = false;
            companion.showRestartFailed();
        }
    }

    // ---- Who has the controller ----

    public boolean companionOwnsInput() { return router.companionOwns(); }

    /** The player's deliberate choice: the Thor's Back button, Ctrl+Alt+Shift+M, or the companion's own chip. */
    void setCompanionOwnsInput(boolean owns) {
        if (ended || owns == router.companionOwns()) return;
        router.setCompanionOwns(owns);
        // Nothing the game was holding stays held on the PC while the companion has the controller.
        if (owns && stream != null) stream.releaseGameInput();
        updatePill();
        notifyCompanion();
    }

    /**
     * Routes a key from either screen. False means the game takes it as usual. True means routing
     * consumed it: the ownership key, or a key the companion's controls now own.
     */
    boolean interceptKey(KeyEvent event) {
        if (ended || passesThrough(event)) return false;
        boolean ownershipKey = isOwnershipKey(event);
        switch (router.key(ControllerRouter.keyId(event.getDeviceId(), event.getKeyCode()),
                event.getAction() == KeyEvent.ACTION_DOWN, event.getRepeatCount(), ownershipKey)) {
            case TOGGLE:
                setCompanionOwnsInput(!router.companionOwns());
                return true;
            case DROP:
                return true;
            case COMPANION:
                if (companion != null) companion.onNavKey(event);
                return true;
            case GAME:
            default:
                return false;
        }
    }

    /** Sticks and the hat steer the companion while it owns the controller; otherwise they go to the game. */
    boolean interceptMotion(MotionEvent event) {
        if (ended || !router.companionOwns()) return false;
        if (!event.isFromSource(InputDevice.SOURCE_JOYSTICK) && !event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false;
        if (companion != null) companion.onNavMotion(event);
        return true;
    }

    /** Volume, power and the IME's own keys never change hands. */
    private static boolean passesThrough(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_HOME: case KeyEvent.KEYCODE_APP_SWITCH:
                return true;
            default:
                return (event.getFlags() & (KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_VIRTUAL_HARD_KEY)) != 0;
        }
    }

    /**
     * The Thor's Back button sends Back from a controller that also has a real Select, so the game
     * loses nothing when the companion takes it. A keyboard uses Ctrl+Alt+Shift+M, beside Moonlight's
     * own Ctrl+Alt+Shift shortcuts.
     */
    static boolean isOwnershipKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_M && event.isCtrlPressed() && event.isAltPressed() && event.isShiftPressed()) {
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_BACK) return false;
        InputDevice device = event.getDevice();
        if (device == null || !(event.isFromSource(InputDevice.SOURCE_GAMEPAD) || ControllerHandler.isGameControllerDevice(device))) {
            return false;
        }
        return device.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)[0];
    }

    // ---- Companion actions ----

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

    boolean quitPending() { return quitPending; }

    /** What the companion says when the PC answered but kept the game running. */
    static final String QUIT_KEPT = "Your PC kept it running.";

    void quitGame() {
        if (ended || quitPending) return;
        if (simulated) {
            // The preview can rehearse a failed quit: the debug activity asks for it.
            if (simulateQuitFailure && companion != null) { companion.showQuitFailed(QUIT_KEPT); return; }
            end();
            return;
        }
        quitPending = true;
        notifyCompanion();
        final CompanionView ownerCompanion = companion;
        final android.app.Activity owner = ownerCompanion != null ? (android.app.Activity) ownerCompanion : stream;
        new Thread(() -> {
            String failure = null;
            try {
                NvHTTP http = new NvHTTP(host.activeAddress, host.httpsPort, null, null, null, broker);
                if (!http.quitApp()) failure = QUIT_KEPT;
            } catch (Exception e) {
                failure = e instanceof PluginProtocol.BrokerException ? e.getMessage() : QUIT_KEPT;
            }
            final String message = failure;
            if (owner != null) owner.runOnUiThread(() -> {
                quitPending = false;
                if (ended) return;
                if (message == null) end();
                else if (companion != null) {
                    companion.showQuitFailed(message);
                    companion.onSessionChanged();
                }
                else Toast.makeText(owner, message, Toast.LENGTH_LONG).show();
            });
        }, "Foldcade-plugin-quit").start();
    }

    /** Sends a keycap's keys to the PC. */
    void sendShortcut(PcShortcut shortcut) { sendSteps(shortcut.steps()); }

    /** Sends one key with modifiers held around it, as the controller keyboard's Ctrl and Alt make. */
    void sendStroke(int vk, int modifiers) { sendSteps(PcShortcut.stroke(vk, modifiers)); }

    private void sendSteps(java.util.List<PcShortcut.Step> steps) {
        if (ended || connection == null) return;
        for (PcShortcut.Step step : steps) connection.sendKeyboardInput(step.key, step.direction, step.modifiers, (byte) 0);
    }

    /** Mutes this device's media volume. The session puts it back when it ends. */
    void setMuted(Context context, boolean muted) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        // Android counts volume 0 as muted too; unmuting there means the player wants to hear it.
        if (!muted && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 4), 0);
        }
        mutedHere = muted;
    }

    boolean isMuted(Context context) {
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        return audio != null && audio.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /** Moonlight's controller mouse: the stick moves the cursor, A and B click. */
    void setPadMouse(boolean on) {
        if (simulated) simulatedMouse = on;
        else if (stream != null) stream.setPadMouse(on);
        updatePill();
        notifyCompanion();
    }

    /**
     * The pill on the game's screen: where the controller is, or that its stick is the mouse.
     * The companion calls this each second too, since a long Start press can turn the mouse off.
     */
    void updatePill() {
        if (stream == null) return;
        if (router.companionOwns()) stream.showPill(FoldcadeStreamActivity.Pill.CONTROLLER_AWAY);
        else if (padMouse()) stream.showPill(FoldcadeStreamActivity.Pill.STICK_MOUSE);
        else stream.showPill(null);
    }

    boolean padMouse() { return simulated ? simulatedMouse : stream != null && stream.padMouse(); }

    private void notifyCompanion() { if (companion != null) companion.onSessionChanged(); }

    // ---- Input to the PC ----

    boolean key(KeyEvent event) {
        return !ended && stream != null && stream.deliverKey(event);
    }

    boolean motion(MotionEvent event) {
        return !ended && stream != null && stream.onGenericMotionEvent(event);
    }

    void text(String text) { if (!ended && connection != null) connection.sendUtf8Text(text); }
    void keyStroke(int code) {
        key(new KeyEvent(KeyEvent.ACTION_DOWN, code));
        key(new KeyEvent(KeyEvent.ACTION_UP, code));
    }
    void move(short x, short y) {
        if (ended || connection == null) return;
        if (desktopMouse) connection.sendMouseMoveAsMousePosition(x, y, (short) screen[0], (short) screen[1]);
        else connection.sendMouseMove(x, y);
    }
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
