package com.limelight.foldcade.ui

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.ComposeView
import com.limelight.foldcade.Appearance

/**
 * Why a play request could not start, on the screen it was launched on, in the companion's
 * message style rather than a stock Android dialog. A, B, Back or a tap closes it; the
 * activity's own key handling calls [onClose] for the buttons.
 */
object StartError {
    @JvmStatic
    fun show(activity: ComponentActivity, message: String, onClose: Runnable) {
        val scale = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val fold = Fold(Appearance.AFTERGLOW, scale)
        // Full screen like the stream it stands in for: no status or navigation bar around the card.
        @Suppress("DEPRECATION")
        activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        activity.setContentView(ComposeView(activity).apply {
            setContent {
                CompositionLocalProvider(LocalFold provides fold) {
                    var target by remember { mutableFloatStateOf(0f) }
                    LaunchedEffect(Unit) { target = 1f }
                    val shown by eased(target, fold.durationIsland)
                    Box(Modifier.fillMaxSize().drawBehind { drawRect(fold.background) }, contentAlignment = Alignment.Center) {
                        MessagePanel(
                            title = "Can't start the stream", body = message, primary = "OK", secondary = null,
                            primaryFocused = true, secondaryFocused = false,
                            onPrimary = { onClose.run() }, onSecondary = { onClose.run() },
                            modifier = Modifier.rise(fold) { shown },
                        )
                    }
                }
            }
        })
    }
}
