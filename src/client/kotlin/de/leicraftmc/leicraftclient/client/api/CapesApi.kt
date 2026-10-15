package de.leicraftmc.leicraftclient.client.api

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** One cosmetic a player has equipped, as listed by `/users/<name>.cfg`. */
data class CosmeticEntry(
    /** Backend-relative path to the CPM model, e.g. `assets/items/creeper/model.cfg`. */
    val modelPath: String,
    /** Backend-relative path to the model's texture. */
    val texturePath: String,
) {
    /** Stable id used for caching; derived from the model path. */
    val id: String get() = modelPath.removeSuffix("/model.cfg").substringAfterLast('/')
}

/**
 * Talks to the LeiCraft cosmetics backend (`capesapi.leicraftmc.de` by default).
 *
 * The routes are the ones OptiFine's cosmetics system used, which is why the old
 * setup worked by redirecting OptiFine's host:
 *  - `GET /users/<name>.cfg`    -> JSON list of equipped cosmetics
 *  - `GET /capes/<name>.png`    -> 302 to the player's cape image
 *  - `GET /<relative path>`     -> model and texture files
 *
 * Every call blocks, so callers must stay off the render thread.
 */
object CapesApi {

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    /** Returns the cosmetics a player has equipped, or an empty list if they have none. */
    fun fetchCosmetics(username: String): List<CosmeticEntry> {
        val body = getString("/users/${encode(username)}.cfg") ?: return emptyList()
        return try {
            val root = JsonParser.parseString(body) as? JsonObject ?: return emptyList()
            val items = root.getAsJsonArray("items") ?: return emptyList()
            items.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                if (!obj.isActive()) return@mapNotNull null
                val model = obj.str("model") ?: return@mapNotNull null
                val texture = obj.str("texture") ?: return@mapNotNull null
                CosmeticEntry(model.trimStart('/'), texture.trimStart('/'))
            }
        } catch (e: Exception) {
            // The old backend answers with a plain-text error instead of JSON when
            // a player is unknown, so this is an expected path rather than a fault.
            LeiCraftClient.LOGGER.debug("No cosmetics for {}", username, e)
            emptyList()
        }
    }

    /** Returns the player's cape PNG, or null if they have none. */
    fun fetchCape(username: String): ByteArray? = getPng("/capes/${encode(username)}.png")

    /** Returns a CPM model file from a backend-relative path. */
    fun fetchModel(path: String): String? = getString("/$path")

    /** Returns a texture from a backend-relative path. */
    fun fetchTexture(path: String): ByteArray? = getPng("/$path")

    // -- plumbing -------------------------------------------------------------

    private fun getString(path: String): String? {
        val response = send(path, HttpResponse.BodyHandlers.ofString()) ?: return null
        return response.body()
    }

    private fun getPng(path: String): ByteArray? {
        val response = send(path, HttpResponse.BodyHandlers.ofByteArray()) ?: return null
        val bytes = response.body()
        // The backend redirects to a plain HTTP fallback host when a player has no
        // cape; that hop is refused as a downgrade and we land on an HTML error
        // page instead, so check for a real PNG rather than trusting the status.
        if (bytes == null || bytes.size < PNG_MAGIC.size) return null
        for (i in PNG_MAGIC.indices) {
            if (bytes[i] != PNG_MAGIC[i]) return null
        }
        return bytes
    }

    private fun <T> send(path: String, handler: HttpResponse.BodyHandler<T>): HttpResponse<T>? {
        val url = ClientConfig.apiBaseUrl + path
        return try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "LeiCraftClient")
                .GET()
                .build()
            val response = http.send(request, handler)
            if (response.statusCode() !in 200..299) {
                LeiCraftClient.LOGGER.debug("{} returned {}", url, response.statusCode())
                return null
            }
            response
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.debug("Request to {} failed", url, e)
            null
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private fun JsonObject.str(key: String): String? =
        runCatching { get(key).asString }.getOrNull()

    /** The backend writes `active` as the string `"true"`, not a JSON boolean. */
    private fun JsonObject.isActive(): Boolean {
        val element = get("active") ?: return true
        return runCatching { element.asBoolean }.getOrNull()
            ?: runCatching { element.asString.equals("true", ignoreCase = true) }.getOrNull()
            ?: true
    }
}
