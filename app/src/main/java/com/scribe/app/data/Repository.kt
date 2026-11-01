package com.scribe.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.scribe.app.ai.BuiltInTemplates
import com.scribe.app.util.AppJson
import com.scribe.app.util.formatShort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

fun RecordingEntity.toDomain(): Recording = Recording(
    id = id,
    title = title,
    autoTitle = autoTitle,
    createdAt = createdAt,
    durationMs = durationMs,
    localPath = localPath,
    mimeType = mimeType,
    tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
    starred = starred,
    bookmarks = runCatching { AppJson.decodeFromString(ListSerializer(Bookmark.serializer()), bookmarksJson) }.getOrDefault(emptyList()),
    transcript = transcriptJson?.let { runCatching { AppJson.decodeFromString(Transcript.serializer(), it) }.getOrNull() },
    notes = notesJson?.let { runCatching { AppJson.decodeFromString(Notes.serializer(), it) }.getOrNull() },
    status = runCatching { Status.valueOf(status) }.getOrDefault(Status.RECORDED),
    error = error,
    templateId = templateId,
    cloudAudio = cloudAudio,
    updatedAt = updatedAt,
    syncedAt = syncedAt,
)

class Repository(private val context: Context, private val db: AppDatabase, private val settings: SettingsStore) {
    val audioDir: File = File(context.filesDir, "audio").apply { mkdirs() }
    private val dao = db.recordings()

    fun observeAll(): Flow<List<Recording>> = dao.observeAll().map { list -> list.map { it.toDomain() } }
    fun observe(id: String): Flow<Recording?> = dao.observe(id).map { it?.toDomain() }
    suspend fun get(id: String): Recording? = dao.get(id)?.toDomain()
    suspend fun entity(id: String): RecordingEntity? = dao.get(id)
    suspend fun all(): List<Recording> = dao.all().map { it.toDomain() }
    suspend fun upsertEntity(e: RecordingEntity) = dao.upsert(e)

    fun newAudioFile(ext: String = "m4a"): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        return id to File(audioDir, "$id.$ext")
    }

    suspend fun createRecording(
        id: String,
        file: File,
        mimeType: String,
        durationMs: Long,
        bookmarks: List<Bookmark> = emptyList(),
        title: String? = null,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        dao.upsert(
            RecordingEntity(
                id = id,
                title = title ?: "Recording ${formatShort(createdAt)}",
                autoTitle = true,
                createdAt = createdAt,
                durationMs = durationMs,
                localPath = file.absolutePath,
                mimeType = mimeType,
                bookmarksJson = AppJson.encodeToString(ListSerializer(Bookmark.serializer()), bookmarks),
                templateId = settings.value.defaultTemplate,
                status = Status.RECORDED.name,
            )
        )
    }

    /** Copies an audio/video file from another app into Scribe's library. */
    suspend fun importUri(uri: Uri): String = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        var name = "Imported audio"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        val mime = cr.getType(uri) ?: guessMime(name)
        val ext = name.substringAfterLast('.', "").lowercase().ifBlank { if (mime.contains("mp4") || mime.contains("m4a")) "m4a" else "audio" }
        val (id, file) = newAudioFile(ext)
        cr.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val duration = audioDuration(file)
        createRecording(id, file, mime, duration, title = name.substringBeforeLast('.'))
        id
    }

    fun audioDuration(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            setDataSource(file.absolutePath)
            val d = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            release()
            d
        }
    }.getOrDefault(0L)

    private fun guessMime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "webm" -> "audio/webm"
        "mp4" -> "video/mp4"
        else -> "audio/mp4"
    }

    suspend fun update(id: String, touch: Boolean = true, block: (RecordingEntity) -> RecordingEntity) {
        val e = dao.get(id) ?: return
        val n = block(e)
        dao.upsert(if (touch) n.copy(updatedAt = System.currentTimeMillis()) else n)
    }

    suspend fun setStatus(id: String, status: Status, error: String? = null) = dao.setStatus(id, status.name, error)

    suspend fun setTranscript(id: String, t: Transcript) = update(id) {
        it.copy(transcriptJson = AppJson.encodeToString(Transcript.serializer(), t))
    }

    suspend fun setNotes(id: String, n: Notes) = update(id) { e ->
        e.copy(
            notesJson = AppJson.encodeToString(Notes.serializer(), n),
            title = if (e.autoTitle && n.title.isNotBlank()) n.title else e.title,
            tags = if (e.tags.isBlank()) n.topics.joinToString(",") else e.tags,
        )
    }

    suspend fun rename(id: String, title: String) = update(id) { it.copy(title = title, autoTitle = false) }
    suspend fun setTags(id: String, tags: List<String>) = update(id) { it.copy(tags = tags.joinToString(",") { t -> t.trim() }) }
    suspend fun toggleStar(id: String) = update(id) { it.copy(starred = !it.starred) }
    suspend fun setTemplate(id: String, templateId: String) = update(id) { it.copy(templateId = templateId) }

    suspend fun addBookmark(id: String, b: Bookmark) = update(id) { e ->
        val list = e.toDomain().bookmarks + b
        e.copy(bookmarksJson = AppJson.encodeToString(ListSerializer(Bookmark.serializer()), list.sortedBy { it.timeMs }))
    }

    suspend fun toggleAction(id: String, actionId: String) {
        val rec = get(id) ?: return
        val n = rec.notes ?: return
        val updated = n.copy(actionItems = n.actionItems.map { if (it.id == actionId) it.copy(done = !it.done) else it })
        update(id) { it.copy(notesJson = AppJson.encodeToString(Notes.serializer(), updated)) }
    }

    suspend fun renameSpeaker(id: String, raw: String, name: String) {
        val t = get(id)?.transcript ?: return
        setTranscript(id, t.copy(speakerNames = t.speakerNames + (raw to name.trim())))
    }

    suspend fun editSegment(id: String, index: Int, text: String) {
        val t = get(id)?.transcript ?: return
        setTranscript(id, t.copy(segments = t.segments.mapIndexed { i, s -> if (i == index) s.copy(text = text) else s }))
    }

    suspend fun deleteLocal(id: String) {
        dao.get(id)?.localPath?.let { File(it).delete() }
        dao.delete(id)
        db.chat().clear("rec:$id")
    }

    // ---------- Templates ----------

    fun observeTemplates(): Flow<List<Template>> = db.templates().observeAll().map { custom ->
        BuiltInTemplates.all + custom.map { Template(it.id, it.name, it.description, it.instructions, false) }
    }

    suspend fun template(id: String): Template =
        BuiltInTemplates.find(id)
            ?: db.templates().all().firstOrNull { it.id == id }?.let { Template(it.id, it.name, it.description, it.instructions) }
            ?: BuiltInTemplates.all.first()

    suspend fun saveTemplate(t: Template) = db.templates().upsert(TemplateEntity(t.id, t.name, t.description, t.instructions))
    suspend fun deleteTemplate(id: String) = db.templates().delete(id)
    suspend fun customTemplates(): List<Template> = db.templates().all().map { Template(it.id, it.name, it.description, it.instructions) }

    // ---------- Chat ----------

    fun observeChat(scope: String) = db.chat().observe(scope)
    suspend fun chatHistory(scope: String) = db.chat().list(scope)
    suspend fun addChat(scope: String, role: String, text: String) = db.chat().insert(ChatEntity(scope = scope, role = role, text = text))
    suspend fun clearChat(scope: String) = db.chat().clear(scope)

    fun observeLibraryWithTemplates() = combine(observeAll(), observeTemplates()) { a, b -> a to b }
}
