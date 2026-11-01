package com.scribe.app.cloud

import com.scribe.app.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

class CloudException(val code: Int, message: String) : Exception(message)

fun newHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.MINUTES)
    .writeTimeout(15, TimeUnit.MINUTES)
    .callTimeout(30, TimeUnit.MINUTES)
    .retryOnConnectionFailure(true)
    .build()

/** Authenticated HTTP for Google APIs with one automatic token refresh on 401. */
class CloudHttp(val client: OkHttpClient, private val auth: GoogleAuth) {

    suspend fun <T> call(build: () -> Request.Builder, handle: (Response) -> T): T = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val token = auth.token()
            val req = build().header("Authorization", "Bearer $token").build()
            val resp = client.newCall(req).execute()
            if (resp.code == 401 && attempt == 0) {
                resp.close()
                auth.invalidate(token)
                attempt++
                continue
            }
            if (resp.code in listOf(429, 500, 502, 503, 504) && attempt < 3) {
                resp.close()
                attempt++
                kotlinx.coroutines.delay(2000L * attempt * attempt)
                continue
            }
            return@withContext resp.use { r ->
                if (!r.isSuccessful) throw errorFrom(r)
                handle(r)
            }
        }
        throw IllegalStateException()
    }

    private fun errorFrom(r: Response): CloudException {
        val body = runCatching { r.body?.string() }.getOrNull().orEmpty()
        val msg = runCatching {
            AppJson.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull() ?: body.take(400)
        val hint = when (r.code) {
            403 -> " (check the account has Storage Object Admin + Vertex AI User roles and the APIs are enabled)"
            404 -> " (not found — check project / bucket / region / model name)"
            else -> ""
        }
        return CloudException(r.code, "HTTP ${r.code}: $msg$hint")
    }
}
