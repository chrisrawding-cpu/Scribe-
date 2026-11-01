package com.scribe.app.sync

import android.content.Context
import com.scribe.app.cloud.Gcs
import com.scribe.app.data.AppDatabase
import com.scribe.app.data.Notes
import com.scribe.app.data.RecordingDoc
import com.scribe.app.data.RecordingEntity
import com.scribe.app.data.Repository
import com.scribe.app.data.SettingsStore
import com.scribe.app.data.Status
import com.scribe.app.data.Template
import com.scribe.app.data.Transcript
import com.scribe.app.data.toDomain
import com.scribe.app.util.AppJson
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Mirrors the library into the user's Cloud Storage bucket:
 *
 *   gs://BUCKET/scribe/recordings/{id}/audio.{ext}
 *   gs://BUCKET/scribe/recordings/{id}/record.json   (metadata + transcript + notes + mind map)
 *   gs://BUCKET/scribe/templates.json
 *   gs://BUCKET/scribe/digests/{yyyy-mm-dd}.md
 *
 * Any device signed in to the same bucket can restore the full library.
 */
class SyncManager(
    private val context: Context,
    private val repo: Repository,
    private val gcs: Gcs,
    private val db: AppDatabase,
    private val settings: SettingsStore,
) {
    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    private fun prefix(id: String) = "${gcs.root}/recordings/$id/"
    fun recordName(id: String) = prefix(id) + "record.json"

    fun audioName(e: RecordingEntity): String {
        val ext = e.localPath?.substringAfterLast('.', "")?.takeIf { it.length in 2..5 }
            ?: when {
                e.mimeType.contains("mpeg") -> "mp3"
                e.mimeType.contains("wav") -> "wav"
                e.mimeType.contains("ogg") -> "ogg"
                else -> "m4a"
            }
        return prefix(e.id) + "audio.$ext"
    }

    suspend fun uploadAudio(id: String): String {
        val e = repo.entity(id) ?: error("Recording not found")
        val name = audioName(e)
        if (!e.cloudAudio) {
            val f = File(e.localPath ?: error("Audio file missing"))
            require(f.exists()) { "Audio file missing on this device" }
            gcs.upload(name, f, e.mimeType)
            repo.update(id, touch = false) { it.copy(cloudAudio = true) }
        }
        return gcs.gsUri(name)
    }

    suspend fun push(id: String) {
        val e = repo.entity(id) ?: return
        if (!e.cloudAudio && e.localPath != null && File(e.localPath).exists()) uploadAudio(id)
        val r = (repo.entity(id) ?: return).toDomain()
        val doc = RecordingDoc(
            id = r.id, title = r.title, autoTitle = r.autoTitle, createdAt = r.createdAt, durationMs = r.durationMs,
            mimeType = r.mimeType, audioObject = audioName(e), tags = r.tags, starred = r.starred, bookmarks = r.bookmarks,
            transcript = r.transcript, notes = r.notes, templateId = r.templateId, updatedAt = r.updatedAt,
        )
        gcs.uploadText(recordName(id), AppJson.encodeToString(RecordingDoc.serializer(), doc))
        val now = System.currentTimeMillis()
        repo.update(id, touch = false) { it.copy(syncedAt = maxOf(now, it.updatedAt)) }
        if (!settings.value.keepLocalAudio) {
            repo.entity(id)?.localPath?.let { File(it).delete() }
        }
    }

    /** Pushes local changes, applies pending deletions, then pulls anything new from the bucket. */
    suspend fun syncAll(): String {
        var pushed = 0
        var pulled = 0
        pendingDeletes().forEach { id ->
            gcs.deletePrefix(prefix(id))
            removePendingDelete(id)
        }
        for (e in db.recordings().all()) {
            if (e.syncedAt < e.updatedAt || e.syncedAt == 0L) {
                if (e.status in listOf(Status.UPLOADING.name, Status.TRANSCRIBING.name, Status.SUMMARIZING.name)) continue
                push(e.id); pushed++
            }
        }
        pushTemplates()
        val remote = gcs.list("${gcs.root}/recordings/").filter { it.name.endsWith("/record.json") }
        for (obj in remote) {
            val id = obj.name.removeSuffix("/record.json").substringAfterLast('/')
            if (id in pendingDeletes()) continue
            val local = db.recordings().get(id)
            if (local != null && obj.updated <= local.syncedAt) continue
            val text = gcs.downloadText(obj.name) ?: continue
            val doc = runCatching { AppJson.decodeFromString(RecordingDoc.serializer(), text) }.getOrNull() ?: continue
            if (local != null && doc.updatedAt <= local.updatedAt) {
                repo.update(id, touch = false) { it.copy(syncedAt = obj.updated) }
                continue
            }
            repo.upsertEntity(
                RecordingEntity(
                    id = doc.id, title = doc.title, autoTitle = doc.autoTitle, createdAt = doc.createdAt,
                    durationMs = doc.durationMs, localPath = local?.localPath, mimeType = doc.mimeType,
                    tags = doc.tags.joinToString(","), starred = doc.starred,
                    bookmarksJson = AppJson.encodeToString(ListSerializer(com.scribe.app.data.Bookmark.serializer()), doc.bookmarks),
                    transcriptJson = doc.transcript?.let { AppJson.encodeToString(Transcript.serializer(), it) },
                    notesJson = doc.notes?.let { AppJson.encodeToString(Notes.serializer(), it) },
                    status = if (doc.notes != null || doc.transcript != null) Status.READY.name else Status.RECORDED.name,
                    templateId = doc.templateId.ifBlank { "auto" },
                    cloudAudio = true,
                    updatedAt = doc.updatedAt,
                    syncedAt = maxOf(obj.updated, doc.updatedAt),
                )
            )
            pulled++
        }
        pullTemplates()
        prefs.edit().putLong("lastSync", System.currentTimeMillis()).apply()
        return "Uploaded $pushed, downloaded $pulled"
    }

    val lastSync: Long get() = prefs.getLong("lastSync", 0)

    /** Makes sure the audio is on this device (downloads from the bucket if needed). */
    suspend fun ensureLocalAudio(id: String): File {
        val e = repo.entity(id) ?: error("Recording not found")
        e.localPath?.let { val f = File(it); if (f.exists()) return f }
        require(e.cloudAudio) { "Audio is not available on this device or in the cloud" }
        val name = audioName(e)
        val f = File(repo.audioDir, "$id.${name.substringAfterLast('.')}")
        gcs.download(name, f)
        repo.update(id, touch = false) { it.copy(localPath = f.absolutePath) }
        return f
    }

    suspend fun deleteEverywhere(id: String) {
        repo.deleteLocal(id)
        addPendingDelete(id)
        runCatching {
            gcs.deletePrefix(prefix(id))
            removePendingDelete(id)
        }
    }

    suspend fun pushTemplates() {
        val list = repo.customTemplates()
        gcs.uploadText("${gcs.root}/templates.json", AppJson.encodeToString(ListSerializer(Template.serializer()), list))
    }

    private suspend fun pullTemplates() {
        val text = gcs.downloadText("${gcs.root}/templates.json") ?: return
        val list = runCatching { AppJson.decodeFromString(ListSerializer(Template.serializer()), text) }.getOrNull() ?: return
        val existing = repo.customTemplates().map { it.id }.toSet()
        list.filter { it.id !in existing }.forEach { repo.saveTemplate(it) }
    }

    suspend fun saveDigest(day: String, markdown: String) {
        File(context.filesDir, "digests").apply { mkdirs() }.resolve("$day.md").writeText(markdown)
        runCatching { gcs.uploadText("${gcs.root}/digests/$day.md", markdown, "text/markdown") }
    }

    suspend fun loadDigest(day: String): String? {
        val f = File(context.filesDir, "digests/$day.md")
        if (f.exists()) return f.readText()
        return runCatching { gcs.downloadText("${gcs.root}/digests/$day.md") }.getOrNull()?.also {
            f.parentFile?.mkdirs(); f.writeText(it)
        }
    }

    private fun pendingDeletes(): Set<String> = prefs.getStringSet("pendingDeletes", emptySet())!!.toSet()
    private fun addPendingDelete(id: String) = prefs.edit().putStringSet("pendingDeletes", pendingDeletes() + id).apply()
    private fun removePendingDelete(id: String) = prefs.edit().putStringSet("pendingDeletes", pendingDeletes() - id).apply()
}
