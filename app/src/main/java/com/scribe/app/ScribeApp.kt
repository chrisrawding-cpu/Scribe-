package com.scribe.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.scribe.app.ai.AiService
import com.scribe.app.cloud.CloudHttp
import com.scribe.app.cloud.Gcs
import com.scribe.app.cloud.GoogleAuth
import com.scribe.app.cloud.Vertex
import com.scribe.app.cloud.newHttpClient
import com.scribe.app.data.AppDatabase
import com.scribe.app.data.Repository
import com.scribe.app.data.SettingsStore
import com.scribe.app.sync.SyncManager
import com.scribe.app.work.Jobs

const val NOTIF_CHANNEL_RECORDING = "recording"
const val NOTIF_CHANNEL_PROCESSING = "processing"

class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val db = AppDatabase.create(context)
    val repo = Repository(context, db, settings)
    private val client = newHttpClient()
    val auth = GoogleAuth(context, settings, client)
    private val http = CloudHttp(client, auth)
    val gcs = Gcs(http, settings)
    val vertex = Vertex(http, settings)
    val ai = AiService(vertex, settings)
    val sync = SyncManager(context, repo, gcs, db, settings)
}

class ScribeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(NOTIF_CHANNEL_PROCESSING, "AI processing & sync", NotificationManager.IMPORTANCE_LOW))
        Jobs.schedulePeriodicSync(this)
    }
}

val Context.app: ScribeApp get() = applicationContext as ScribeApp
