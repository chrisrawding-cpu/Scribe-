package com.scribe.app.ui.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.scribe.app.BuildConfig
import com.scribe.app.cloud.NeedsSignInException
import com.scribe.app.data.AppSettings
import com.scribe.app.data.AuthMode
import com.scribe.app.ui.components.SectionCard
import com.scribe.app.ui.components.container
import com.scribe.app.ui.components.toast
import com.scribe.app.util.formatDateTime
import kotlinx.coroutines.launch
import java.security.MessageDigest

private val REGIONS = listOf("global", "europe-west2", "europe-west1", "europe-west4", "us-central1", "us-east4", "asia-southeast1", "australia-southeast1")

fun signingSha1(ctx: Context): String = runCatching {
    val pm = ctx.packageManager
    val sig = if (Build.VERSION.SDK_INT >= 28) {
        pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners?.firstOrNull()
    } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
    }
    MessageDigest.getInstance("SHA-1").digest(sig!!.toByteArray()).joinToString(":") { "%02X".format(it) }
}.getOrDefault("unknown")

@Composable
fun SettingsScreen(onTemplates: () -> Unit) {
    val c = container()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by c.settings.state.collectAsStateWithLifecycle()
    val templates by remember { c.repo.observeTemplates() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var testing by remember { mutableStateOf(false) }
    var testLog by remember { mutableStateOf("") }
    var bucketMissing by remember { mutableStateOf(false) }
    val sha1 = remember { signingSha1(ctx) }

    fun set(block: (AppSettings) -> AppSettings) = c.settings.update(block)

    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            runCatching { c.auth.resultFromIntent(res.data) }
                .onSuccess { c.auth.onAuthorized(it); ctx.toast("Signed in") }
                .onFailure { ctx.toast("Sign-in failed: ${it.message}") }
        } else ctx.toast("Sign-in cancelled")
    }
    val keyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val json = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
            c.auth.importServiceAccount(json)
        }.onSuccess { ctx.toast("Service account imported: ${it.client_email}") }
            .onFailure { ctx.toast("Import failed: ${it.message}") }
    }

    fun signIn() = scope.launch {
        try {
            val r = c.auth.beginAuthorization()
            if (r.hasResolution()) {
                authLauncher.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build())
            } else {
                c.auth.onAuthorized(r); ctx.toast("Signed in")
            }
        } catch (e: Exception) {
            testLog = "Sign-in failed: ${e.message}\n\nIf this says DEVELOPER_ERROR (10), create an Android OAuth client in your Cloud project for package ${ctx.packageName} with SHA-1 $sha1 (see below)."
        }
    }

    fun test() = scope.launch {
        testing = true
        bucketMissing = false
        val log = StringBuilder()
        try {
            log.appendLine("Getting access token…")
            testLog = log.toString()
            c.auth.token()
            log.appendLine("✓ Authenticated")
            testLog = log.toString()
            if (c.gcs.bucketExists()) log.appendLine("✓ Bucket gs://${s.bucket} is accessible")
            else { log.appendLine("✗ Bucket gs://${s.bucket} not found — tap Create bucket"); bucketMissing = true }
            testLog = log.toString()
            if (c.vertex.ping(s.model)) log.appendLine("✓ Vertex AI model ${s.model} works in ${s.region}")
            else log.appendLine("✗ Model ${s.model} isn't available in ${s.region}. Tap Detect models.")
        } catch (e: NeedsSignInException) {
            log.appendLine("✗ ${e.message}")
        } catch (e: Exception) {
            log.appendLine("✗ ${e.message}")
        }
        testLog = log.toString()
        testing = false
    }

    fun detectModels() = scope.launch {
        testing = true
        val log = StringBuilder("Probing Gemini models in ${s.region}…\n")
        testLog = log.toString()
        try {
            val fast = AppSettings.CANDIDATE_MODELS.firstOrNull { m ->
                val ok = runCatching { c.vertex.ping(m) }.getOrDefault(false)
                log.appendLine((if (ok) "✓ " else "✗ ") + m); testLog = log.toString(); ok
            }
            val pro = AppSettings.CANDIDATE_PRO_MODELS.firstOrNull { m ->
                val ok = runCatching { c.vertex.ping(m) }.getOrDefault(false)
                log.appendLine((if (ok) "✓ " else "✗ ") + m); testLog = log.toString(); ok
            }
            if (fast != null) set { it.copy(model = fast, proModel = pro.orEmpty()) }
            log.appendLine(if (fast != null) "\nUsing $fast for transcription" + (pro?.let { " and $it for summaries & chat" } ?: "") else "\nNo models responded — check the region, that the Vertex AI API is enabled, and your permissions.")
        } catch (e: Exception) { log.appendLine("✗ ${e.message}") }
        testLog = log.toString()
        testing = false
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionCard("1 · Your Google Cloud project") {
                Text("Everything is stored in your bucket and processed by Vertex AI in your project — it never leaves your organisation's Google Cloud.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(s.projectId, { v -> set { it.copy(projectId = v) } }, Modifier.fillMaxWidth(), label = { Text("Project ID") }, singleLine = true)
                OutlinedTextField(s.bucket, { v -> set { it.copy(bucket = v) } }, Modifier.fillMaxWidth(), label = { Text("Cloud Storage bucket") }, placeholder = { Text("my-company-scribe") }, singleLine = true)
                Dropdown("Vertex AI region", s.region, REGIONS, editable = true) { v -> set { it.copy(region = v) } }
                OutlinedTextField(s.model, { v -> set { it.copy(model = v) } }, Modifier.fillMaxWidth(), label = { Text("Gemini model (transcription)") }, singleLine = true)
                OutlinedTextField(s.proModel, { v -> set { it.copy(proModel = v) } }, Modifier.fillMaxWidth(), label = { Text("Model for summaries & chat (optional)") }, placeholder = { Text("defaults to the model above") }, singleLine = true)
            }

            SectionCard("2 · Sign in") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(s.authMode == AuthMode.OAUTH, { set { it.copy(authMode = AuthMode.OAUTH) }; c.auth.invalidate() }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Work Google account") }
                    SegmentedButton(s.authMode == AuthMode.SERVICE_ACCOUNT, { set { it.copy(authMode = AuthMode.SERVICE_ACCOUNT) }; c.auth.invalidate() }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Service account") }
                }
                if (s.authMode == AuthMode.OAUTH) {
                    Text(if (s.accountEmail.isNotBlank()) "Signed in as ${s.accountEmail}" else "Not signed in", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { signIn() }) { Text(if (s.accountEmail.isBlank()) "Sign in with Google" else "Switch account") }
                        if (s.accountEmail.isNotBlank()) OutlinedButton(onClick = { c.auth.signOut() }) { Text("Sign out") }
                    }
                } else {
                    Text(if (s.serviceAccountEmail.isNotBlank()) "Using ${s.serviceAccountEmail}" else "No key imported", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { keyPicker.launch(arrayOf("application/json", "*/*")) }) { Text("Import JSON key") }
                        if (s.serviceAccountEmail.isNotBlank()) OutlinedButton(onClick = { c.auth.signOut() }) { Text("Remove") }
                    }
                }
            }

            SectionCard("3 · Check it works") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { test() }, enabled = !testing) { Text("Test connection") }
                    FilledTonalButton(onClick = { detectModels() }, enabled = !testing) { Text("Detect models") }
                    if (testing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
                if (bucketMissing) OutlinedButton(onClick = {
                    scope.launch {
                        runCatching { c.gcs.createBucket(s.region) }
                            .onSuccess { bucketMissing = false; testLog += "✓ Created bucket gs://${s.bucket}\n" }
                            .onFailure { testLog += "✗ ${it.message}\n" }
                    }
                }) { Text("Create bucket gs://${s.bucket}") }
                if (testLog.isNotBlank()) SelectionContainer { Text(testLog, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard("AI processing") {
                ToggleRow("Transcribe & summarise automatically", s.autoProcess) { v -> set { it.copy(autoProcess = v) } }
                ToggleRow("Only use Wi-Fi for uploads & AI", s.wifiOnly) { v -> set { it.copy(wifiOnly = v) } }
                OutlinedTextField(s.language, { v -> set { it.copy(language = v) } }, Modifier.fillMaxWidth(), label = { Text("Spoken language hint (optional)") }, placeholder = { Text("auto-detect, e.g. English (UK)") }, singleLine = true)
                Dropdown("Default summary template", templates.firstOrNull { it.id == s.defaultTemplate }?.name ?: s.defaultTemplate, templates.map { it.name }) { name ->
                    templates.firstOrNull { it.name == name }?.let { t -> set { it.copy(defaultTemplate = t.id) } }
                }
                TextButton(onClick = onTemplates) { Text("Manage templates…") }
            }

            SectionCard("Recording") {
                ToggleRow("High quality audio (48 kHz, 128 kbps)", s.highQuality) { v -> set { it.copy(highQuality = v) } }
                Text("Tip: add the “Scribe record” tile to Quick Settings, or long-press the app icon, for one-tap recording.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("Cloud sync") {
                ToggleRow("Sync automatically", s.autoSync) { v -> set { it.copy(autoSync = v) } }
                ToggleRow("Keep audio on this phone after upload", s.keepLocalAudio) { v -> set { it.copy(keepLocalAudio = v) } }
                OutlinedTextField(s.rootPrefix, { v -> set { it.copy(rootPrefix = v) } }, Modifier.fillMaxWidth(), label = { Text("Folder in bucket") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { scope.launch {
                        runCatching { c.sync.syncAll() }.onSuccess { ctx.toast(it) }.onFailure { ctx.toast("Sync failed: ${it.message}") }
                    } }, enabled = s.isCloudConfigured) { Text("Sync / restore now") }
                    Spacer(Modifier.width(12.dp))
                    Text(if (c.sync.lastSync > 0) "Last: ${formatDateTime(c.sync.lastSync)}" else "Never synced", style = MaterialTheme.typography.bodySmall)
                }
                Text("Restoring on a new phone: enter the same project and bucket, sign in, then tap Sync.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard("For your Cloud admin") {
                Text("To sign in with a Google account, create an OAuth client of type Android in the Cloud project (APIs & Services → Credentials) with:",
                    style = MaterialTheme.typography.bodySmall)
                CopyRow("Package name", ctx.packageName, ctx)
                CopyRow("SHA-1", sha1, ctx)
                Text("Required APIs: Vertex AI API, Cloud Storage. Roles for the user/service account: Vertex AI User and Storage Object Admin on the bucket.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Scribe ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(value, onChange)
    }
}

@Composable
private fun CopyRow(label: String, value: String, ctx: Context) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, value))
            ctx.toast("Copied")
        }) { Icon(Icons.Filled.ContentCopy, "Copy") }
    }
}

@Composable
fun Dropdown(label: String, value: String, options: List<String>, editable: Boolean = false, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (editable) onSelect(it) },
            readOnly = !editable,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(if (editable) MenuAnchorType.PrimaryEditable else MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(o); expanded = false }) }
        }
    }
}
