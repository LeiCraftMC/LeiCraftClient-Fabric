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
 * Read fields directly; they are only written on the main thread during load
 * and from the options screen. Parsing is deliberately lenient so a hand-edited
 * file with a missing or malformed entry falls back to the default instead of
 * breaking startup.
 */
object ClientConfig {

    const val DEFAULT_API_BASE: String = "https://api.client.leicraftmc.de"
    const val DEFAULT_WEBSITE: String = "https://client.leicraftmc.de"
    const val DEFAULT_CACHE_SECONDS: Long = 600
    const val MIN_CACHE_SECONDS: Long = 30

    /** The V1 OptiFine-style backend; configs written by older builds still point at it. */
    private const val LEGACY_API_BASE = "https://capesapi.leicraftmc.de"

    /** One parsed config file, kept separate from the live fields so loading is testable. */
    data class Snapshot(
        val apiBaseUrl: String = DEFAULT_API_BASE,
        val websiteUrl: String = DEFAULT_WEBSITE,
        val capesEnabled: Boolean = true,
        val cosmeticsEnabled: Boolean = true,
        val accountServicesEnabled: Boolean = true,
        val cacheSeconds: Long = DEFAULT_CACHE_SECONDS,
        /** True when an old value was rewritten, so the file should be saved back. */
        val migrated: Boolean = false,
    ) {
        companion object {
            fun fromJson(root: JsonObject): Snapshot {
                val storedApi = root.string("apiBaseUrl")
                val api = ClientConfig.migrateApiBaseUrl(storedApi)
                return Snapshot(
                    apiBaseUrl = api,
                    websiteUrl = (root.string("websiteUrl") ?: DEFAULT_WEBSITE).trim().trimEnd('/')
                        .ifEmpty { DEFAULT_WEBSITE },
                    capesEnabled = root.bool("capesEnabled", true),
                    cosmeticsEnabled = root.bool("cosmeticsEnabled", true),
                    accountServicesEnabled = root.bool("accountServicesEnabled", true),
                    cacheSeconds = root.long("cacheSeconds", DEFAULT_CACHE_SECONDS).coerceAtLeast(MIN_CACHE_SECONDS),
                    // Missing keys are written back too, so the file lists every option.
                    migrated = storedApi != null && storedApi.trim().trimEnd('/') != api ||
                        KEYS.any { !root.has(it) },
                )
            }

            private val KEYS = listOf(
                "apiBaseUrl", "websiteUrl", "capesEnabled", "cosmeticsEnabled",
                "accountServicesEnabled", "cacheSeconds",
            )
        }

        fun toJson(): JsonObject = JsonObject().apply {
            addProperty("apiBaseUrl", apiBaseUrl)
            addProperty("websiteUrl", websiteUrl)
            addProperty("capesEnabled", capesEnabled)
            addProperty("cosmeticsEnabled", cosmeticsEnabled)
            addProperty("accountServicesEnabled", accountServicesEnabled)
            addProperty("cacheSeconds", cacheSeconds)
        }
    }

    /** Base URL of the LeiCraft Client API, without a trailing slash. */
    var apiBaseUrl: String = DEFAULT_API_BASE
        private set

    /** The LeiCraft Client website, opened by the options screen's link button. */
    var websiteUrl: String = DEFAULT_WEBSITE
        private set

    /** Whether custom capes are applied. Toggled from the options screen. */
    var capesEnabled: Boolean = true

    /** Whether custom cosmetics are rendered. Toggled from the options screen. */
    var cosmeticsEnabled: Boolean = true

    /**
     * Whether the mod signs in to the API with the Minecraft session (handshake,
     * linking, wardrobe). When off, it only reads public player profiles.
     */
    var accountServicesEnabled: Boolean = true

    /** How long a fetched player profile stays valid, in seconds. */
    var cacheSeconds: Long = DEFAULT_CACHE_SECONDS
        private set

    private val path: Path
        get() = FabricLoader.getInstance().configDir.resolve("${LeiCraftClient.MOD_ID}.json")

    /**
     * Replaces the V1 backend address with the V2 API. Anything else the user
     * set on purpose (a staging or local API) is kept.
     */
    fun migrateApiBaseUrl(stored: String?): String {
        val cleaned = stored?.trim()?.trimEnd('/').orEmpty()
        if (cleaned.isEmpty()) return DEFAULT_API_BASE
        if (cleaned.equals(LEGACY_API_BASE, ignoreCase = true) ||
            cleaned.equals(LEGACY_API_BASE.replace("https://", "http://"), ignoreCase = true)
        ) {
            return DEFAULT_API_BASE
        }
        return cleaned
    }

    fun load() {
        val file = path
        if (!Files.exists(file)) {
            save()
            return
        }
        try {
            val snapshot = Snapshot.fromJson(JsonParser.parseString(Files.readString(file)).asJsonObject)
            apply(snapshot)
            if (snapshot.migrated) {
                LeiCraftClient.LOGGER.info("Updated {} to the current format", file.fileName)
                save()
            }
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Could not read {}, using defaults", file, e)
        }
    }

    fun save() {
        val snapshot = Snapshot(
            apiBaseUrl = apiBaseUrl,
            websiteUrl = websiteUrl,
            capesEnabled = capesEnabled,
            cosmeticsEnabled = cosmeticsEnabled,
            accountServicesEnabled = accountServicesEnabled,
            cacheSeconds = cacheSeconds,
        )
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(snapshot.toJson()))
        } catch (e: Exception) {
            LeiCraftClient.LOGGER.warn("Could not write {}", path, e)
        }
    }

    private fun apply(snapshot: Snapshot) {
        apiBaseUrl = snapshot.apiBaseUrl
        websiteUrl = snapshot.websiteUrl
        capesEnabled = snapshot.capesEnabled
        cosmeticsEnabled = snapshot.cosmeticsEnabled
        accountServicesEnabled = snapshot.accountServicesEnabled
        cacheSeconds = snapshot.cacheSeconds
    }
}

private fun JsonObject.string(key: String): String? =
    runCatching { get(key)?.takeUnless { it.isJsonNull }?.asString }.getOrNull()

private fun JsonObject.bool(key: String, fallback: Boolean): Boolean =
    runCatching { get(key).asBoolean }.getOrNull() ?: fallback

private fun JsonObject.long(key: String, fallback: Long): Long =
    runCatching { get(key).asLong }.getOrNull() ?: fallback
