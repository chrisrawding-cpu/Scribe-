package com.scribe.app.cloud

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.scribe.app.data.AppSettings
import com.scribe.app.data.AuthMode
import com.scribe.app.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import com.scribe.app.util.AppJson

const val CLOUD_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

class NeedsSignInException(val pendingIntent: PendingIntent?, message: String) : Exception(message)

@Serializable
data class ServiceAccountKey(
    val type: String = "",
    val project_id: String = "",
    val private_key: String = "",
    val client_email: String = "",
    val token_uri: String = "https://oauth2.googleapis.com/token",
)

/**
 * Supplies OAuth access tokens for Google Cloud APIs.
 *
 * Two modes:
 *  - OAUTH: the user's own work Google account via Google Play services' AuthorizationClient
 *    (requires an Android OAuth client registered in the Cloud project for this app's SHA-1).
 *  - SERVICE_ACCOUNT: a JSON key file imported into the app's private storage.
 */
class GoogleAuth(private val context: Context, private val settings: SettingsStore, private val http: OkHttpClient) {
    private val mutex = Mutex()
    private var cachedToken: String? = null
    private var cachedExpiry: Long = 0
    private var cachedMode: AuthMode? = null

    private val saFile get() = File(context.filesDir, "service_account.json")

    fun authorizationRequest(email: String? = null): AuthorizationRequest {
        val b = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(CLOUD_SCOPE)))
        if (!email.isNullOrBlank()) b.setAccount(Account(email, "com.google"))
        return b.build()
    }

    /** Starts the interactive / silent OAuth flow. Caller launches the pending intent if [AuthorizationResult.hasResolution]. */
    suspend fun beginAuthorization(email: String? = null): AuthorizationResult =
        Identity.getAuthorizationClient(context).authorize(authorizationRequest(email)).await()

    fun resultFromIntent(data: android.content.Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)

    fun onAuthorized(result: AuthorizationResult) {
        val email = result.toGoogleSignInAccount()?.email.orEmpty()
        val token = result.accessToken
        settings.update { it.copy(authMode = AuthMode.OAUTH, accountEmail = email.ifBlank { it.accountEmail.ifBlank { "signed-in" } }) }
        if (token != null) {
            cachedToken = token
            cachedExpiry = System.currentTimeMillis() + 45 * 60_000
            cachedMode = AuthMode.OAUTH
        }
    }

    fun importServiceAccount(json: String): ServiceAccountKey {
        val key = AppJson.decodeFromString(ServiceAccountKey.serializer(), json)
        require(key.client_email.isNotBlank() && key.private_key.contains("PRIVATE KEY")) { "This is not a service account JSON key file" }
        saFile.writeText(json)
        settings.update {
            it.copy(
                authMode = AuthMode.SERVICE_ACCOUNT,
                serviceAccountEmail = key.client_email,
                projectId = it.projectId.ifBlank { key.project_id },
            )
        }
        invalidate()
        return key
    }

    fun signOut() {
        invalidate()
        saFile.delete()
        settings.update { it.copy(accountEmail = "", serviceAccountEmail = "") }
    }

    fun invalidate(token: String? = null) {
        val t = token ?: cachedToken
        cachedToken = null
        cachedExpiry = 0
        if (t != null && cachedMode == AuthMode.OAUTH) {
            runCatching { GoogleAuthUtil.clearToken(context, t) }
        }
    }

    suspend fun token(): String = mutex.withLock {
        val s = settings.value
        val now = System.currentTimeMillis()
        cachedToken?.let { if (cachedMode == s.authMode && now < cachedExpiry) return it }
        val token = when (s.authMode) {
            AuthMode.OAUTH -> oauthToken(s)
            AuthMode.SERVICE_ACCOUNT -> serviceAccountToken()
        }
        cachedToken = token
        cachedMode = s.authMode
        cachedExpiry = now + 45 * 60_000
        token
    }

    private suspend fun oauthToken(s: AppSettings): String {
        if (s.accountEmail.isBlank()) throw NeedsSignInException(null, "Sign in with your Google account in Settings")
        val result = try {
            beginAuthorization(s.accountEmail.takeIf { it.contains('@') })
        } catch (e: Exception) {
            throw NeedsSignInException(null, "Google sign-in failed: ${e.message}")
        }
        if (result.hasResolution()) throw NeedsSignInException(result.pendingIntent, "Google needs you to confirm access — open Settings and tap Sign in")
        return result.accessToken ?: throw NeedsSignInException(null, "No access token returned; sign in again in Settings")
    }

    private suspend fun serviceAccountToken(): String = withContext(Dispatchers.IO) {
        if (!saFile.exists()) throw NeedsSignInException(null, "Import a service account key in Settings")
        val key = AppJson.decodeFromString(ServiceAccountKey.serializer(), saFile.readText())
        val now = System.currentTimeMillis() / 1000
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = enc.encodeToString(
            """{"iss":"${key.client_email}","scope":"$CLOUD_SCOPE","aud":"${key.token_uri}","iat":$now,"exp":${now + 3600}}""".toByteArray()
        )
        val pem = key.private_key
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace(Regex("\\s"), "")
        val pk = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(pk)
            update("$header.$claims".toByteArray())
        }.sign()
        val jwt = "$header.$claims.${enc.encodeToString(sig)}"
        val req = Request.Builder().url(key.token_uri).post(
            FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                .add("assertion", jwt)
                .build()
        ).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw CloudException(resp.code, "Service account token error: $body")
            AppJson.parseToJsonElement(body).jsonObject["access_token"]?.jsonPrimitive?.content
                ?: throw CloudException(resp.code, "No access_token in response")
        }
    }
}
