package com.scribe.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.scribe.app.ui.ScribeRoot
import com.scribe.app.ui.components.toast
import com.scribe.app.ui.theme.ScribeTheme
import com.scribe.app.work.Jobs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

sealed interface NavEvent {
    data object Record : NavEvent
    data object OpenRecorder : NavEvent
    data class OpenRecording(val id: String) : NavEvent
}

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_RECORD = "com.scribe.app.action.RECORD"
        const val ACTION_OPEN_RECORDER = "com.scribe.app.action.OPEN_RECORDER"
    }

    private val events = MutableSharedFlow<NavEvent>(replay = 1, extraBufferCapacity = 4)
    val navEvents: SharedFlow<NavEvent> get() = events

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ScribeTheme { ScribeRoot(navEvents) { events.resetReplayCache() } } }
        if (savedInstanceState == null) handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            ACTION_RECORD -> events.tryEmit(NavEvent.Record)
            ACTION_OPEN_RECORDER -> events.tryEmit(NavEvent.OpenRecorder)
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { import(it) }
            Intent.ACTION_VIEW -> intent.data?.let { import(it) }
        }
    }

    private fun import(uri: Uri) {
        val c = app.container
        lifecycleScope.launch {
            try {
                val id = c.repo.importUri(uri)
                if (c.settings.value.autoProcess && c.settings.value.isCloudConfigured) Jobs.process(this@MainActivity, id)
                events.tryEmit(NavEvent.OpenRecording(id))
            } catch (e: Exception) {
                toast("Import failed: ${e.message}")
            }
        }
    }

}
