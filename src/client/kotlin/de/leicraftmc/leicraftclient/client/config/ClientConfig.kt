package de.leicraftmc.leicraftclient.client.config

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.LeiCraftClient
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Mod configuration, stored as `config/leicraftclient.json`.
 *
 * Read fields directly; they are only written on the main thread during load.
 * Parsing is deliberately lenient so a hand-edited file with a missing or
 * malformed entry falls back to the default instead of breaking startup.
 */
object ClientConfig {

    private const val DEFAULT_API_BASE = "https://capesapi.leicraftmc.de"

    /** Base URL of the cosmetics backend, without a trailing slash. */
    var apiBaseUrl: String = DEFAULT_API_BASE
        private set

    /** Whether custom capes are applied. */
    var capesEnabled: Boolean = true
        private set

    /** Whether custom cosmetics are rendered. */
    var cosmeticsEnabled: Boolean = true
        private set

    /** How long a fetched player profile stays valid, in seconds. */
    var cacheSeconds: Long = 600
        private set

    private val path: Path
        get() = FabricLoader.getInstance().configDir.resolve("${LeiCraftClient.MOD_ID}.json")

    fun load() {
        val file = path
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            apiBaseUrl = root.string("apiBaseUrl", DEFAULT_API_BASE).trimEnd('/')
            capesEnabled = root.bool("capesEnabled", true)
            cosmeticsEnabled = root.bool("cosmeticsEnabled", true)
            cacheSeconds = root.long("cacheSeconds", 600).coerceAtLeast(30)
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Could not read {}, using defaults", file, e)
        }
    }

    fun save() {
        val root = JsonObject().apply {
            addProperty("apiBaseUrl", apiBaseUrl)
            addProperty("capesEnabled", capesEnabled)
            addProperty("cosmeticsEnabled", cosmeticsEnabled)
            addProperty("cacheSeconds", cacheSeconds)
        }
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(root))
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Could not write {}", path, e)
        }
    }

    private fun JsonObject.string(key: String, fallback: String): String =
        runCatching { get(key).asString }.getOrNull() ?: fallback

    private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
        runCatching { get(key).asBoolean }.getOrNull() ?: fallback

    private fun JsonObject.long(key: String, fallback: Long): Long =
        runCatching { get(key).asLong }.getOrNull() ?: fallback
}
