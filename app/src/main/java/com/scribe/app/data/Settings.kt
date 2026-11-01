package com.scribe.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AuthMode { OAUTH, SERVICE_ACCOUNT }

data class AppSettings(
    val projectId: String = "",
    val bucket: String = "",
    val region: String = "global",
    val model: String = DEFAULT_MODEL,
    val proModel: String = "",
    val authMode: AuthMode = AuthMode.OAUTH,
    val accountEmail: String = "",
    val serviceAccountEmail: String = "",
    val autoProcess: Boolean = true,
    val autoSync: Boolean = true,
    val wifiOnly: Boolean = false,
    val language: String = "",
    val defaultTemplate: String = "auto",
    val highQuality: Boolean = false,
    val keepLocalAudio: Boolean = true,
    val rootPrefix: String = "scribe",
) {
    val isCloudConfigured: Boolean
        get() = projectId.isNotBlank() && bucket.isNotBlank() &&
            (if (authMode == AuthMode.OAUTH) accountEmail.isNotBlank() else serviceAccountEmail.isNotBlank())

    /** Model used for summaries / chat; falls back to the main model. */
    val reasoningModel: String get() = proModel.ifBlank { model }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        /** Probed in order by "Detect models" — newest first. */
        val CANDIDATE_MODELS = listOf(
            "gemini-3.5-flash", "gemini-3-flash", "gemini-3-flash-preview",
            "gemini-2.5-flash", "gemini-2.0-flash-001",
        )
        val CANDIDATE_PRO_MODELS = listOf(
            "gemini-3.5-pro", "gemini-3-pro", "gemini-3-pro-preview", "gemini-2.5-pro",
        )
    }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load() = AppSettings(
        projectId = prefs.getString("projectId", "")!!,
        bucket = prefs.getString("bucket", "")!!,
        region = prefs.getString("region", "global")!!,
        model = prefs.getString("model", AppSettings.DEFAULT_MODEL)!!,
        proModel = prefs.getString("proModel", "")!!,
        authMode = runCatching { AuthMode.valueOf(prefs.getString("authMode", "OAUTH")!!) }.getOrDefault(AuthMode.OAUTH),
        accountEmail = prefs.getString("accountEmail", "")!!,
        serviceAccountEmail = prefs.getString("serviceAccountEmail", "")!!,
        autoProcess = prefs.getBoolean("autoProcess", true),
        autoSync = prefs.getBoolean("autoSync", true),
        wifiOnly = prefs.getBoolean("wifiOnly", false),
        language = prefs.getString("language", "")!!,
        defaultTemplate = prefs.getString("defaultTemplate", "auto")!!,
        highQuality = prefs.getBoolean("highQuality", false),
        keepLocalAudio = prefs.getBoolean("keepLocalAudio", true),
        rootPrefix = prefs.getString("rootPrefix", "scribe")!!,
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putString("projectId", s.projectId.trim())
            .putString("bucket", s.bucket.trim().removePrefix("gs://").trimEnd('/'))
            .putString("region", s.region.trim().ifBlank { "global" })
            .putString("model", s.model.trim().ifBlank { AppSettings.DEFAULT_MODEL })
            .putString("proModel", s.proModel.trim())
            .putString("authMode", s.authMode.name)
            .putString("accountEmail", s.accountEmail)
            .putString("serviceAccountEmail", s.serviceAccountEmail)
            .putBoolean("autoProcess", s.autoProcess)
            .putBoolean("autoSync", s.autoSync)
            .putBoolean("wifiOnly", s.wifiOnly)
            .putString("language", s.language.trim())
            .putString("defaultTemplate", s.defaultTemplate)
            .putBoolean("highQuality", s.highQuality)
            .putBoolean("keepLocalAudio", s.keepLocalAudio)
            .putString("rootPrefix", s.rootPrefix.trim().trim('/').ifBlank { "scribe" })
            .apply()
        _state.value = load()
    }
}
