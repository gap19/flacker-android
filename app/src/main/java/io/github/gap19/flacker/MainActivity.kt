package io.github.gap19.flacker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.gap19.flacker.ui.FlackerRoot

class MainActivity : ComponentActivity() {
    companion object {
        const val OPEN_NOW = "open_now"
    }

    private val model: AppModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Only a fresh launch from the notification opens the player; rotation or a theme
        // change recreates the activity with the same intent and must not open it again.
        if (savedInstanceState == null) handle(intent)
        setContent { FlackerRoot(model) }
        holdFirstFrame()
    }

    /**
     * Keep the system splash on screen until the saved shelf is shown, so the app never opens
     * on an empty collection. A safety limit lets it draw anyway if loading takes unusually long.
     */
    private fun holdFirstFrame() {
        val started = android.os.SystemClock.uptimeMillis()
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val ready = !model.loading.value || android.os.SystemClock.uptimeMillis() - started > 800
                if (ready) content.viewTreeObserver.removeOnPreDrawListener(this)
                return ready
            }
        })
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(OPEN_NOW, false) != true) return
        model.open(Screen.Now)
        intent.removeExtra(OPEN_NOW)
        setIntent(intent)
    }
}
