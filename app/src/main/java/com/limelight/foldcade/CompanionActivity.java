package com.limelight.foldcade;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.nvstream.input.MouseButtonPacket;
import com.limelight.nvstream.jni.MoonBridge;

/** Bottom-panel controls. Non-focusable except while the local IME is in use. */
public class CompanionActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FoldcadeSession session;
    private RemoteKeyboardView editor;
    private Button keyboard;
    private TouchpadGesture gesture;
    private boolean keyboardOpen, showImeOnFocus, imeWasVisible;
    private AlertDialog dialog;
    private TextView stats;
    private View touchpad;
    private final Runnable updateStats = new Runnable() {
        @Override public void run() {
            if (session == null || session.ended || isFinishing()) return;
            if (session.simulated) {
                float offset = (float)Math.sin(android.os.SystemClock.uptimeMillis() / 2000.0);
                session.stats = new StreamStats(60 + offset / 4, 4, 2.1f + offset / 10,
                        0, 1920, 1080, MoonBridge.VIDEO_FORMAT_H265);
            }
            stats.setText(session.stats == null ? getString(R.string.foldcade_connecting) : session.stats.display());
            handler.postDelayed(this, 1000);
        }
    };

    protected FoldcadeSession obtainSession() { return FoldcadeSession.find(getIntent()); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        session = obtainSession();
        if (session == null) { finish(); return; }
        session.attach(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Color.rgb(16, 20, 26));
        getWindow().setNavigationBarColor(Color.rgb(16, 20, 26));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildContent();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                if (keyboardOpen) {
                    if (insets.isVisible(WindowInsets.Type.ime())) imeWasVisible = true;
                    else if (imeWasVisible) handler.post(() -> setKeyboardOpen(false));
                }
                return view.onApplyWindowInsets(insets);
            });
        }
        handler.post(updateStats);
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // The pad supplies a content description and calls performClick on release.
    private void buildContent() {
        int foreground = Color.rgb(232, 236, 242), muted = Color.rgb(150, 163, 179);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(16));
        root.setBackgroundColor(Color.rgb(16, 20, 26));
        TextView title = label(session.title, 21, foreground);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        TextView host = label(session.pcName, 13, muted);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(-1, -2);
        hostParams.topMargin = dp(4);
        root.addView(host, hostParams);
        stats = label(getString(R.string.foldcade_connecting), 11, muted);
        if (android.os.Build.VERSION.SDK_INT >= 26) stats.setAutoSizeTextTypeUniformWithConfiguration(8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(-1, dp(32));
        statsParams.topMargin = dp(8);
        stats.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(stats, statsParams);

        FrameLayout pad = new FrameLayout(this);
        touchpad = pad;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(Color.rgb(24, 30, 39));
        surface.setCornerRadius(dp(16));
        surface.setStroke(dp(1), Color.rgb(42, 52, 66));
        pad.setBackground(surface);
        pad.setContentDescription(getString(R.string.foldcade_touchpad));
        LinearLayout.LayoutParams padParams = new LinearLayout.LayoutParams(-1, 0, 1);
        padParams.topMargin = dp(8);
        padParams.bottomMargin = dp(16);
        root.addView(pad, padParams);
        TextView hint = new TextView(this);
        hint.setText(R.string.foldcade_touchpad_hint);
        hint.setTextColor(muted);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setLineSpacing(dp(7), 1);
        pad.addView(hint, new FrameLayout.LayoutParams(-1, -1));
        boolean seenHint = getPreferences(MODE_PRIVATE).getBoolean("touchpadHintSeen", false);
        if (seenHint) hint.setVisibility(View.GONE);
        gesture = new TouchpadGesture(new TouchpadGesture.Output() {
            @Override public void move(short x, short y) { session.move(x, y); }
            @Override public void button(boolean right, boolean down) {
                session.button(right ? MouseButtonPacket.BUTTON_RIGHT : MouseButtonPacket.BUTTON_LEFT, down);
            }
            @Override public void scroll(short vertical, short horizontal) { session.scroll(vertical, horizontal); }
        }, ViewConfiguration.get(this).getScaledTouchSlop() / getResources().getDisplayMetrics().density);
        pad.setOnTouchListener((view, event) -> {
            handleTouch(event);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                view.performClick();
                if (hint.getVisibility() == View.VISIBLE) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("touchpadHintSeen", true).apply();
                    hint.animate().alpha(0).setDuration(400).withEndAction(() -> hint.setVisibility(View.GONE)).start();
                }
            }
            return true;
        });
        pad.setOnClickListener(view -> { });

        LinearLayout buttons = new LinearLayout(this);
        keyboard = button(R.string.foldcade_keyboard, view -> toggleKeyboard());
        Button disconnect = button(R.string.foldcade_disconnect, view -> session.end());
        Button quit = button(R.string.foldcade_quit, view -> confirmQuit());
        buttons.addView(keyboard, new LinearLayout.LayoutParams(0, dp(52), 1));
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, dp(52), 1);
        middle.setMargins(dp(8), 0, dp(8), 0);
        buttons.addView(disconnect, middle);
        buttons.addView(quit, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        editor = new RemoteKeyboardView(this, session, () -> setKeyboardOpen(false));
        root.addView(editor, new LinearLayout.LayoutParams(1, 1));
        setContentView(root);
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private Button button(int text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(232, 236, 242));
        button.setPadding(dp(4), 0, dp(4), 0);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(Color.rgb(37, 47, 61));
        shape.setCornerRadius(dp(10));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.rgb(67, 85, 108)), shape, null));
        button.setOnClickListener(listener);
        return button;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void handleTouch(MotionEvent event) {
        float x = 0, y = 0;
        int count = event.getPointerCount();
        for (int i = 0; i < count; i++) { x += event.getX(i); y += event.getY(i); }
        float density = getResources().getDisplayMetrics().density;
        x /= count * density;
        y /= count * density;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: gesture.down(x, y, event.getEventTime()); break;
            case MotionEvent.ACTION_POINTER_DOWN: gesture.pointers(count, x, y, false); break;
            case MotionEvent.ACTION_POINTER_UP: gesture.pointers(count, x, y, true); break;
            case MotionEvent.ACTION_MOVE: gesture.move(x, y, count, event.getEventTime()); break;
            case MotionEvent.ACTION_UP: gesture.up(event.getEventTime()); break;
            case MotionEvent.ACTION_CANCEL: gesture.cancel(); break;
            default: break;
        }
    }

    void toggleKeyboard() { setKeyboardOpen(!keyboardOpen); }

    private void setKeyboardOpen(boolean open) {
        keyboardOpen = open;
        showImeOnFocus = open;
        imeWasVisible = false;
        touchpad.setVisibility(open ? View.GONE : View.VISIBLE);
        stats.setVisibility(open ? View.GONE : View.VISIBLE);
        keyboard.setText(open ? R.string.foldcade_hide_keyboard : R.string.foldcade_keyboard);
        InputMethodManager ime = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (open) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            editor.requestFocus();
            editor.post(() -> {
                if (keyboardOpen && hasWindowFocus()) showIme();
            });
        } else {
            ime.hideSoftInputFromWindow(editor.getWindowToken(), 0);
            editor.clearFocus();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }

    private void showIme() {
        showImeOnFocus = false;
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && keyboardOpen && showImeOnFocus) editor.post(this::showIme);
    }

    public void confirmDisconnect() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new AlertDialog.Builder(this).setTitle(R.string.foldcade_disconnect_confirm)
                .setMessage(R.string.foldcade_disconnect_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.foldcade_disconnect, (d, which) -> session.end()).show();
    }

    private void confirmQuit() {
        if (dialog != null && dialog.isShowing()) return;
        dialog = new AlertDialog.Builder(this).setTitle(R.string.foldcade_quit_confirm)
                .setMessage(R.string.foldcade_quit_detail)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.foldcade_quit, (d, which) -> session.quitGame()).show();
    }

    void showMessage(String title, String message) {
        if (isFinishing()) return;
        dialog = new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    @Override public void onBackPressed() {
        if (keyboardOpen) setKeyboardOpen(false); else confirmDisconnect();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && !isController(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
            return true;
        }
        return session != null && session.key(event) || super.dispatchKeyEvent(event);
    }

    private boolean isController(KeyEvent event) {
        return event.isFromSource(InputDevice.SOURCE_GAMEPAD) || event.isFromSource(InputDevice.SOURCE_JOYSTICK);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent event) {
        return session != null && session.motion(event) || super.onGenericMotionEvent(event);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (gesture != null) gesture.cancel();
        if (dialog != null) dialog.dismiss();
        if (session != null) session.end();
        super.onDestroy();
    }
}
