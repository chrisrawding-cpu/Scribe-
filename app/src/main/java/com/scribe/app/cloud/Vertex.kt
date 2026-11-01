package com.scribe.app.cloud

import com.scribe.app.data.SettingsStore
import com.scribe.app.util.AppJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A single content part sent to Gemini. */
sealed interface Part {
    data class Text(val text: String) : Part
    data class GcsFile(val uri: String, val mimeType: String) : Part
}

data class Turn(val role: String, val parts: List<Part>) {
    companion object {
        fun user(text: String) = Turn("user", listOf(Part.Text(text)))
        fun model(text: String) = Turn("model", listOf(Part.Text(text)))
    }
}

data class GenResult(val text: String, val finishReason: String)

/** Vertex AI Gemini generateContent client — all data stays in the user's Cloud project. */
class Vertex(private val http: CloudHttp, private val settings: SettingsStore) {

    private fun endpoint(model: String): String {
        val s = settings.value
        require(s.projectId.isNotBlank()) { "Set your Google Cloud project ID in Settings" }
        val region = s.region.ifBlank { "global" }
        val host = if (region == "global") "aiplatform.googleapis.com" else "$region-aiplatform.googleapis.com"
        return "https://$host/v1/projects/${s.projectId}/locations/$region/publishers/google/models/$model:generateContent"
    }

    suspend fun generate(
        turns: List<Turn>,
        system: String? = null,
        model: String = settings.value.model,
        schema: JsonObject? = null,
        temperature: Double = 0.3,
        maxOutputTokens: Int = 32768,
    ): GenResult {
        val body = buildJsonObject {
            putJsonArray("contents") {
                turns.forEach { t ->
                    addJsonObject {
                        put("role", t.role)
                        putJsonArray("parts") {
                            t.parts.forEach { p ->
                                when (p) {
                                    is Part.Text -> addJsonObject { put("text", p.text) }
                                    is Part.GcsFile -> addJsonObject {
                                        putJsonObject("fileData") {
                                            put("mimeType", p.mimeType)
                                            put("fileUri", p.uri)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (system != null) putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", system) } }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", maxOutputTokens)
                if (schema != null) {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                }
            }
        }.toString()

        val json = http.call({
            Request.Builder().url(endpoint(model)).post(body.toRequestBody("application/json".toMediaType()))
        }) { AppJson.parseToJsonElement(it.body!!.string()).jsonObject }

        val candidates = json["candidates"]?.jsonArray
        if (candidates.isNullOrEmpty()) {
            val reason = json["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.content
            throw CloudException(200, "Gemini returned no answer" + (reason?.let { " (blocked: $it)" } ?: ""))
        }
        val cand = candidates[0].jsonObject
        val text = cand["content"]?.jsonObject?.get("parts")?.jsonArray
            ?.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            .orEmpty()
        return GenResult(text, cand["finishReason"]?.jsonPrimitive?.content.orEmpty())
    }

    /** Cheap probe used by "Test connection" / model detection. */
    suspend fun ping(model: String): Boolean = try {
        generate(listOf(Turn.user("Reply with the single word OK.")), model = model, maxOutputTokens = 256)
        true
    } catch (e: CloudException) {
        if (e.code == 404 || e.code == 400) false else throw e
    }
}

/** Helpers for building Vertex responseSchema objects (OpenAPI subset). */
object Schema {
    fun str(desc: String? = null) = buildJsonObject { put("type", "STRING"); desc?.let { put("description", it) } }
    fun num(desc: String? = null) = buildJsonObject { put("type", "NUMBER"); desc?.let { put("description", it) } }
    fun arr(items: JsonElement, desc: String? = null) = buildJsonObject {
        put("type", "ARRAY"); put("items", items); desc?.let { put("description", it) }
    }
    fun obj(vararg props: Pair<String, JsonElement>, required: List<String> = props.map { it.first }) = buildJsonObject {
        put("type", "OBJECT")
        put("properties", JsonObject(props.toMap()))
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }
    fun strArr(desc: String? = null) = arr(str(), desc)

    /** Fixed-depth recursive mind map schema (responseSchema does not support $ref). */
    fun mindNode(depth: Int): JsonObject =
        if (depth <= 0) obj("label" to str())
        else obj("label" to str(), "children" to arr(mindNode(depth - 1)), required = listOf("label"))
}
