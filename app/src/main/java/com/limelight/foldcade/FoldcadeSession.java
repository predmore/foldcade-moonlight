package com.limelight.foldcade;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

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
    /** Which stream may still use the broker; see {@link StreamGate}. */
    private final StreamGate gate = new StreamGate();
    /** Stream activities replaced or abandoned on purpose: their finishing never ends the session. */
    private final java.util.Set<FoldcadeStreamActivity> retired = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** The last press of each key the game holds, so a handover can release it on the PC. */
    private final java.util.Map<Long, KeyEvent> heldByGame = new java.util.HashMap<>();
    /** The current stream has finished its startup: its connection is up. */
    private boolean streamStarted;
    /** How long a stopped stream's last broker request may take to come back (a launch allows 60 s). */
    static final long DRAIN_MS = 65_000;
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
        gate.onRefusal((generation, path) -> android.util.Log.i("Foldcade", "Refused " + path + " from replaced stream " + generation +
                " (current " + gate.current() + ")"));
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

    /** The current stream generation, for stream intents, logs and the debug race driver. */
    int gateGeneration() { return gate.current(); }

    /**
     * Admits a stream activity being created, or refuses it (null). Its intent names the generation
     * it was opened for: one queued before a Quit, or for a stream since replaced, is stale, and
     * finishes before it makes a connection. The transport works until the stream is replaced or
     * the game is quit; after that its requests are refused, so a stopped stream cannot launch.
     */
    NvHTTP.RequestTransport admitStream(Intent intent) {
        final int generation = intent.getIntExtra(PluginProtocol.GENERATION, -1);
        if (ended || quitPending || !gate.admits(generation)) {
            Log.i("Foldcade", "Refused a stream opened for generation " + generation + " (current " + gate.current()
                    + (quitPending ? ", quitting" : "") + ")");
            return null;
        }
        return gate.transport((path, query) -> {
            Log.d("Foldcade", "Stream generation " + generation + " sends " + path);
            return request(path, query);
        }, generation);
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
        streamStarted = false;
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
        router.setCompanionAvailable(true);
        if (simulated) applied = CompanionSettings.INSTANCE.restartSnapshot(PreferenceStore.of((Context) activity));
    }

    boolean isStream(FoldcadeStreamActivity activity) { return activity == stream; }

    /** The current stream's connection is up; a Quit from here on leaves it to the PC to end. */
    void streamStarted(FoldcadeStreamActivity activity) { if (activity == stream) streamStarted = true; }

    /**
     * A stream activity finished. Only the current one ends the session; one replaced by a restart,
     * or set aside by a Quit, does not.
     */
    void streamFinished(FoldcadeStreamActivity activity) {
        if (retired.contains(activity)) return;
        if (activity == stream || (stream == null && !restarting && !quitPending)) end();
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
        router.setCompanionAvailable(false);
        gate.advance();
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
        setCompanionOwnsInput(false);
        // The old stream's startup may still be waiting on the PC; from now on it cannot launch.
        int generation = gate.advance();
        retired.add(old);
        notifyCompanion();
        old.finish();
        new Thread(() -> {
            if (oldConnection != null) oldConnection.stop();
            // A request already on its way finishes before the new stream negotiates its key. One
            // that never comes back could still land after it, so then the restart fails instead.
            gate.drainThen(DRAIN_MS, () -> {
                Log.i("Foldcade", "Restarting the stream as generation " + generation);
                main.post(() -> openReplacementStream(generation));
            }, () -> {
                Log.w("Foldcade", "A replaced stream's request is still outstanding; the restart fails");
                main.post(this::restartFailed);
            });
        }, "Foldcade-plugin-restart").start();
    }

    private void openReplacementStream(int generation) {
        // A Quit while the old stream was stopping cancels the restart: nothing may start the game again.
        if (ended || quitPending || !restarting || !gate.admits(generation)) {
            Log.i("Foldcade", "Restart cancelled; no replacement stream opens");
            return;
        }
        Context context = companion != null ? (Context) companion : null;
        // Foldcade sessions only exist on Android 8 and later; see FoldcadePlayActivity.
        if (context == null || android.os.Build.VERSION.SDK_INT < 26) { end(); return; }
        try {
            Intent intent = new Intent(streamIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(PluginProtocol.GENERATION, generation);
            context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(topDisplay).toBundle());
        } catch (RuntimeException e) {
            restartFailed();
        }
    }

    /** The game still runs on the PC. The companion says so, and the session ends when the player has read it. */
    private void restartFailed() {
        if (ended || !restarting) return;
        restarting = false;
        if (companion != null) companion.showRestartFailed();
        else end();
    }

    // ---- Who has the controller ----

    public boolean companionOwnsInput() { return router.companionOwns(); }

    /** The player's deliberate choice: the Thor's Back button, Ctrl+Alt+Shift+M, or the companion's own chip. */
    void setCompanionOwnsInput(boolean owns) {
        if (ended || owns == router.companionOwns()) return;
        // No companion, no one to take the controller (one screen, or the companion was refused).
        if (!router.setCompanionOwns(owns)) return;
        // Nothing the game was holding stays held on the PC: keyboard keys and modifiers come up,
        // and the controllers, sensors included, go quiet until the controller comes back.
        if (owns) releaseKeysHeldByGame("Handover");
        if (stream != null) stream.suspendGameInput(owns);
        updatePill();
        notifyCompanion();
    }

    /**
     * Releases on the PC every key the game holds now. Each one's repeats and physical release are
     * swallowed afterwards ({@link ControllerRouter#releaseHeldByGame}), so nothing presses it again.
     */
    private void releaseKeysHeldByGame(String why) {
        for (Long id : router.releaseHeldByGame()) {
            KeyEvent down = heldByGame.remove(id);
            if (down == null || stream == null) continue;
            Log.i("Foldcade", why + ": released " + KeyEvent.keyCodeToString(down.getKeyCode()) + " for the game");
            stream.deliverKey(KeyEvent.changeAction(down, KeyEvent.ACTION_UP));
        }
        heldByGame.clear();
    }

    /**
     * Routes a key from either screen. False means the game takes it as usual. True means routing
     * consumed it: the ownership key, or a key the companion's controls now own.
     */
    boolean interceptKey(KeyEvent event) {
        if (ended || passesThrough(event)) return false;
        boolean ownershipKey = isOwnershipKey(event);
        long id = ControllerRouter.keyId(event.getDeviceId(), event.getKeyCode());
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        ControllerRouter.Route route = router.key(id, down, event.getRepeatCount(), ownershipKey);
        if (route == ControllerRouter.Route.GAME) {
            if (down) heldByGame.put(id, event); else heldByGame.remove(id);
        }
        switch (route) {
            case TOGGLE:
                setCompanionOwnsInput(!router.companionOwns());
                return true;
            case DROP:
                if (!ownershipKey) Log.d("Foldcade", "Swallowed " + KeyEvent.keyCodeToString(event.getKeyCode())
                        + (down ? " repeat " + event.getRepeatCount() : " release") + " after a handover");
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
        else if (stream != null && !ended) {
            // The panel takes every key while it is up, releases included: let go of what the game
            // holds first, or a key held as it opens would stay down on the PC.
            releaseKeysHeldByGame("End panel");
            stream.showEndPanel(this::end, this::quitGame);
        }
    }

    boolean quitPending() { return quitPending; }

    /** What the companion says when the PC answered but kept the game running. */
    static final String QUIT_KEPT = "Your PC kept it running.";

    /** What it says when a stopped stream's request never came back, so the quit was not sent. */
    static final String QUIT_UNSENT = "The stream didn't stop in time, so the quit wasn't sent.";

    void quitGame() {
        if (ended || quitPending) return;
        if (simulated) {
            // The preview can rehearse a failed quit: the debug activity asks for it.
            if (simulateQuitFailure && companion != null) { companion.showQuitFailed(QUIT_KEPT); return; }
            end();
            return;
        }
        quitPending = true;
        // Nothing may launch or resume the game from here: a restart in progress is called off,
        // and a stream still starting up is set aside before its startup can reach the PC.
        restarting = false;
        gate.advance();
        final NvConnection starting;
        if (stream != null && !streamStarted) {
            retired.add(stream);
            FoldcadeStreamActivity old = stream;
            starting = connection;
            stream = null;
            connection = null;
            old.finish();
        } else {
            starting = null;
        }
        notifyCompanion();
        new Thread(() -> {
            if (starting != null) starting.stop();
            // Any launch a set-aside stream already sent comes back before the cancel goes out. One
            // that never comes back could start the game after the cancel, so then nothing is sent.
            final String[] failure = { null };
            gate.drainThen(DRAIN_MS, () -> {
                Log.i("Foldcade", "Quitting the game");
                try {
                    NvHTTP http = new NvHTTP(host.activeAddress, host.httpsPort, null, null, null, broker);
                    if (!http.quitApp()) failure[0] = QUIT_KEPT;
                } catch (Exception e) {
                    failure[0] = e instanceof PluginProtocol.BrokerException ? e.getMessage() : QUIT_KEPT;
                }
            }, () -> {
                Log.w("Foldcade", "A stream's request is still outstanding; the quit is not sent");
                failure[0] = QUIT_UNSENT;
            });
            final String message = failure[0];
            main.post(() -> {
                quitPending = false;
                if (ended) return;
                if (message == null) end();
                else if (companion != null) {
                    companion.showQuitFailed(message);
                    companion.onSessionChanged();
                }
                else if (stream != null) stream.showNotice(message);
                // No screen left to show it on: a stream set aside for the quit, and no companion.
                else end();
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
