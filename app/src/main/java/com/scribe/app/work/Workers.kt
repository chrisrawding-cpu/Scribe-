package com.scribe.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.scribe.app.NOTIF_CHANNEL_PROCESSING
import com.scribe.app.R
import com.scribe.app.app
import com.scribe.app.cloud.NeedsSignInException
import com.scribe.app.data.Status
import java.io.IOException
import java.util.concurrent.TimeUnit

object Jobs {
    private fun constraints(ctx: Context) = Constraints.Builder()
        .setRequiredNetworkType(if (ctx.app.container.settings.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    /** Upload → transcribe → summarise / mind-map. */
    fun process(ctx: Context, id: String, retranscribe: Boolean = false, renotes: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf("id" to id, "retranscribe" to retranscribe, "renotes" to renotes))
            .setConstraints(constraints(ctx))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("process")
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("process-$id", ExistingWorkPolicy.REPLACE, req)
    }

    fun syncNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints(ctx)).addTag("manual").build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sync-now", ExistingWorkPolicy.REPLACE, req)
    }

    fun schedulePeriodicSync(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(constraints(ctx)).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}

class ProcessWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val c = ctx.app.container

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Processing recording…")

    private fun foreground(text: String): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL_PROCESSING)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Scribe")
            .setContentText(text)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(4242, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(4242, n)
    }

    private suspend fun progress(id: String, status: Status, text: String) {
        c.repo.setStatus(id, status, null)
        runCatching { setForeground(foreground(text)) }
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString("id") ?: return Result.failure()
        val retranscribe = inputData.getBoolean("retranscribe", false)
        val renotes = inputData.getBoolean("renotes", false)
        val repo = c.repo
        repo.get(id) ?: return Result.failure()
        return try {
            check(c.settings.value.isCloudConfigured) { "Connect Google Cloud in Settings first" }

            progress(id, Status.UPLOADING, "Uploading to your Cloud Storage…")
            val gsUri = c.sync.uploadAudio(id)

            var rec = repo.get(id)!!
            if (rec.transcript == null || retranscribe) {
                progress(id, Status.TRANSCRIBING, "Transcribing with Gemini…")
                val t = c.ai.transcribe(gsUri, rec.mimeType, rec.durationMs) { msg ->
                    runCatching { setForegroundAsync(foreground(msg)) }
                }
                val keepNames = rec.transcript?.speakerNames.orEmpty()
                repo.setTranscript(id, t.copy(speakerNames = keepNames))
                rec = repo.get(id)!!
            }

            if (rec.notes == null || renotes || retranscribe) {
                progress(id, Status.SUMMARIZING, "Writing summary & mind map…")
                val transcript = rec.transcript!!
                if (transcript.segments.isEmpty()) {
                    repo.setNotes(id, com.scribe.app.data.Notes(title = "", summary = "_No speech was detected in this recording._", generatedAt = System.currentTimeMillis()))
                } else {
                    val template = repo.template(rec.templateId)
                    val notes = c.ai.generateNotes(rec, transcript, template)
                    // keep "done" state of tasks that still exist
                    val oldDone = rec.notes?.actionItems?.filter { it.done }?.map { it.task }?.toSet().orEmpty()
                    repo.setNotes(id, notes.copy(actionItems = notes.actionItems.map { if (it.task in oldDone) it.copy(done = true) else it }))
                }
            }
            repo.setStatus(id, Status.READY, null)
            runCatching { c.sync.push(id) }
            Result.success()
        } catch (e: NeedsSignInException) {
            repo.setStatus(id, Status.FAILED, e.message)
            Result.failure()
        } catch (e: IOException) {
            if (runAttemptCount < 3) {
                repo.setStatus(id, Status.QUEUED, "Network problem, retrying: ${e.message}")
                Result.retry()
            } else {
                repo.setStatus(id, Status.FAILED, e.message ?: e.toString()); Result.failure()
            }
        } catch (e: Exception) {
            repo.setStatus(id, Status.FAILED, e.message ?: e.toString())
            Result.failure()
        }
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext.app.container
        val manual = tags.contains("manual")
        if (!c.settings.value.isCloudConfigured || (!c.settings.value.autoSync && !manual)) return Result.success()
        return try {
            c.sync.syncAll()
            Result.success()
        } catch (e: NeedsSignInException) {
            Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
