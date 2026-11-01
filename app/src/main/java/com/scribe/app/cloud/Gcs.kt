package com.scribe.app.cloud

import com.scribe.app.data.SettingsStore
import com.scribe.app.util.AppJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.time.Instant

data class GcsObject(val name: String, val updated: Long, val size: Long)

/** Minimal Google Cloud Storage JSON API client. */
class Gcs(private val http: CloudHttp, private val settings: SettingsStore) {
    private val bucket get() = settings.value.bucket.also { require(it.isNotBlank()) { "Set a Cloud Storage bucket in Settings" } }
    val root get() = settings.value.rootPrefix

    private fun objectUrl(name: String): HttpUrl.Builder =
        "https://storage.googleapis.com/storage/v1/b".toHttpUrl().newBuilder()
            .addPathSegment(bucket).addPathSegment("o").addPathSegment(name)

    private fun uploadUrl(name: String): HttpUrl =
        "https://storage.googleapis.com/upload/storage/v1/b".toHttpUrl().newBuilder()
            .addPathSegment(bucket).addPathSegment("o")
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("name", name)
            .build()

    fun gsUri(name: String) = "gs://$bucket/$name"

    suspend fun upload(name: String, file: File, mime: String) {
        http.call({ Request.Builder().url(uploadUrl(name)).post(file.asRequestBody(mime.toMediaType())) }) { }
    }

    suspend fun uploadText(name: String, text: String, mime: String = "application/json") {
        http.call({ Request.Builder().url(uploadUrl(name)).post(text.toRequestBody("$mime; charset=utf-8".toMediaType())) }) { }
    }

    suspend fun download(name: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        http.call({ Request.Builder().url(objectUrl(name).addQueryParameter("alt", "media").build()) }) { r ->
            tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
        }
        tmp.renameTo(dest)
    }

    suspend fun downloadText(name: String): String? = try {
        http.call({ Request.Builder().url(objectUrl(name).addQueryParameter("alt", "media").build()) }) { it.body!!.string() }
    } catch (e: CloudException) {
        if (e.code == 404) null else throw e
    }

    suspend fun list(prefix: String): List<GcsObject> {
        val out = mutableListOf<GcsObject>()
        var pageToken: String? = null
        do {
            val url = "https://storage.googleapis.com/storage/v1/b".toHttpUrl().newBuilder()
                .addPathSegment(bucket).addPathSegment("o")
                .addQueryParameter("prefix", prefix)
                .addQueryParameter("fields", "items(name,updated,size),nextPageToken")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = http.call({ Request.Builder().url(url) }) { AppJson.parseToJsonElement(it.body!!.string()).jsonObject }
            json["items"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                out += GcsObject(
                    name = o["name"]!!.jsonPrimitive.content,
                    updated = runCatching { Instant.parse(o["updated"]!!.jsonPrimitive.content).toEpochMilli() }.getOrDefault(0),
                    size = o["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
                )
            }
            pageToken = json["nextPageToken"]?.jsonPrimitive?.content
        } while (pageToken != null)
        return out
    }

    suspend fun delete(name: String) {
        try {
            http.call({ Request.Builder().url(objectUrl(name).build()).delete() }) { }
        } catch (e: CloudException) {
            if (e.code != 404) throw e
        }
    }

    suspend fun deletePrefix(prefix: String) {
        list(prefix).forEach { delete(it.name) }
    }

    suspend fun bucketExists(): Boolean = try {
        http.call({ Request.Builder().url("https://storage.googleapis.com/storage/v1/b".toHttpUrl().newBuilder().addPathSegment(bucket).build()) }) { true }
    } catch (e: CloudException) {
        if (e.code == 404) false else throw e
    }

    suspend fun createBucket(location: String) {
        val loc = if (location.isBlank() || location == "global") "EU" else location.uppercase()
        val body = buildJsonObject {
            put("name", bucket)
            put("location", loc)
            putJsonObject("iamConfiguration") { putJsonObject("uniformBucketLevelAccess") { put("enabled", true) } }
            putJsonObject("versioning") { put("enabled", false) }
        }.toString()
        val url = "https://storage.googleapis.com/storage/v1/b".toHttpUrl().newBuilder()
            .addQueryParameter("project", settings.value.projectId).build()
        http.call({ Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())) }) { }
    }
}
