package com.limelight.foldcade;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.limelight.foldcade.ui.StreamEndPanel;

/**
 * Debug APK only: the single-screen End panel over a blank stand-in for the stream, in a plain
 * Activity like Game. Back opens it again after it closes. Choices are logged, not acted on.
 */
public final class StreamEndPreviewActivity extends Activity {
    private StreamEndPanel panel;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        View stand = new View(this);
        stand.setBackgroundColor(Color.rgb(0x1B, 0x26, 0x3B));
        setContentView(stand);
        open();
    }

    private void open() {
        panel = StreamEndPanel.show(this,
                () -> { Log.i("Foldcade", "End panel: Disconnect"); panel = null; },
                () -> { Log.i("Foldcade", "End panel: Quit game"); panel = null; },
                () -> { Log.i("Foldcade", "End panel: closed"); panel = null; });
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (panel != null && panel.key(event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B) {
            if (event.getAction() == KeyEvent.ACTION_UP) open();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
