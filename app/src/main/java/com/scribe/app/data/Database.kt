package com.scribe.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val title: String,
    val autoTitle: Boolean = true,
    val createdAt: Long,
    val durationMs: Long = 0,
    val localPath: String? = null,
    val mimeType: String = "audio/mp4",
    val tags: String = "",
    val starred: Boolean = false,
    val bookmarksJson: String = "[]",
    val transcriptJson: String? = null,
    val notesJson: String? = null,
    val status: String = Status.RECORDED.name,
    val error: String? = null,
    val templateId: String = "auto",
    val cloudAudio: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val syncedAt: Long = 0,
)

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chat")
data class ChatEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: String,
    val role: String,
    val text: String,
    val ts: Long = System.currentTimeMillis(),
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    fun observe(id: String): Flow<RecordingEntity?>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: String): RecordingEntity?

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    suspend fun all(): List<RecordingEntity>

    @Upsert
    suspend fun upsert(e: RecordingEntity)

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE recordings SET status = :status, error = :error WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY name")
    fun observeAll(): Flow<List<TemplateEntity>>

    @Query("SELECT * FROM templates")
    suspend fun all(): List<TemplateEntity>

    @Upsert
    suspend fun upsert(e: TemplateEntity)

    @Query("DELETE FROM templates WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat WHERE scope = :scope ORDER BY ts, id")
    fun observe(scope: String): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chat WHERE scope = :scope ORDER BY ts, id")
    suspend fun list(scope: String): List<ChatEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: ChatEntity): Long

    @Query("DELETE FROM chat WHERE scope = :scope")
    suspend fun clear(scope: String)
}

@Database(
    entities = [RecordingEntity::class, TemplateEntity::class, ChatEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordings(): RecordingDao
    abstract fun templates(): TemplateDao
    abstract fun chat(): ChatDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "scribe.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
