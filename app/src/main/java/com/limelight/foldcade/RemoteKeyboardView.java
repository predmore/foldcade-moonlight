package com.limelight.foldcade;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** IME editor on the companion's display; committed text uses Moonlight's UTF-8 path. */
@android.annotation.SuppressLint("ViewConstructor") // Created in code with a live session; never inflated.
final class RemoteKeyboardView extends View {
    private final FoldcadeSession session;
    private final Runnable close;
    private final PreIme preIme;

    /** Sees each key before the IME does, so the ownership key is never taken as "close the keyboard". */
    interface PreIme { boolean onKey(KeyEvent event); }

    RemoteKeyboardView(Context context, FoldcadeSession session, Runnable close, PreIme preIme) {
        super(context);
        this.session = session;
        this.close = close;
        this.preIme = preIme;
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
        return preIme.onKey(event) || super.dispatchKeyEventPreIme(event);
    }

    @Override public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) close.run();
            return true;
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override public InputConnection onCreateInputConnection(EditorInfo info) {
        info.inputType = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI;
        if (android.os.Build.VERSION.SDK_INT >= 26) info.imeOptions |= EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        return new BaseInputConnection(this, true) {
            private final Editable composing = new SpannableStringBuilder();
            @Override public Editable getEditable() { return composing; }
            @Override public boolean commitText(CharSequence text, int cursor) {
                session.text(text.toString());
                composing.clear();
                return true;
            }
            @Override public boolean finishComposingText() {
                if (composing.length() > 0) {
                    session.text(composing.toString());
                    composing.clear();
                }
                return super.finishComposingText();
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (before < 0 || after < 0 || before > 128 || after > 128) return false;
                if (composing.length() > 0) return super.deleteSurroundingText(before, after);
                for (int i = 0; i < before; i++) session.keyStroke(KeyEvent.KEYCODE_DEL);
                for (int i = 0; i < after; i++) session.keyStroke(KeyEvent.KEYCODE_FORWARD_DEL);
                return true;
            }
            @Override public boolean deleteSurroundingTextInCodePoints(int before, int after) {
                return deleteSurroundingText(before, after);
            }
            @Override public boolean sendKeyEvent(KeyEvent event) { return session.key(event); }
            @Override public boolean performEditorAction(int action) {
                session.keyStroke(KeyEvent.KEYCODE_ENTER);
                return true;
            }
        };
    }
}
