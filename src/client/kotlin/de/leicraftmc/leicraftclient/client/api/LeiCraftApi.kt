package de.leicraftmc.leicraftclient.client.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.auth.ClientAuth
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Talks to the LeiCraft Client API (`https://api.client.leicraftmc.de` by default).
 *
 * Every call suspends and runs its blocking HTTP exchange on [Dispatchers.IO]
 * behind [BackendGate], so callers only need a coroutine. Results are
 * [ApiResult]s; nothing here throws for HTTP or network failures.
 *
 * Calls under `/v1/client/` carry `Authorization: Bearer <client token>` from
 * [ClientAuth]. When one answers `401`, the token is stale: the handshake is
 * run once more and the call retried with the fresh token.
 *
 * See `API-Server/docs/client-integration.md` for the contract.
 */
object LeiCraftApi {

    /** At most this many players per `POST /v1/players/lookup`. */
    const val MAX_LOOKUP_PLAYERS = 100

    /** The wardrobe's page size; a full page means there may be more. */
    const val WARDROBE_PAGE_SIZE = 50

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val REQUEST_TIMEOUT_SECONDS = 15L

    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
        .build()

    private val userAgent: String by lazy { "LeiCraftClient/${LeiCraftClient.version}" }

    enum class Method { GET, POST, PUT, DELETE }

    // -- public ---------------------------------------------------------------

    /** Render-ready profiles; players without one are simply absent from the list. */
    suspend fun lookup(uuids: List<String>, names: List<String>): ApiResult<List<ApiModels.PlayerProfile>> {
        val body = JsonObject().apply {
            add("uuids", JsonArray().apply { uuids.forEach(::add) })
            add("names", JsonArray().apply { names.forEach(::add) })
        }
        return send(Method.POST, LOOKUP_PATH, body, null, ApiModels.PlayerProfile::readList)
    }

    /** Downloads an asset's bytes (no envelope; assets are plain files). */
    suspend fun download(url: String, maxBytes: Int): ApiResult<ByteArray> {
        val uri = resolve(url) ?: return ApiResult.Error(ApiResult.NETWORK_ERROR, "Invalid asset URL")
        return BackendGate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                        .header("User-Agent", userAgent)
                        .GET()
                        .build()
                    val response = http.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    val bytes = response.body()
                    when {
                        response.statusCode() !in 200..299 -> ApiResult.Error(response.statusCode(), "HTTP ${response.statusCode()}")
                        bytes == null -> ApiResult.Error(response.statusCode(), "Empty response")
                        bytes.size > maxBytes -> ApiResult.Error(response.statusCode(), "Asset too large (${bytes.size} bytes)")
                        else -> ApiResult.Ok(bytes, "")
                    }
                } catch (e: IOException) {
                    ApiResult.Error(ApiResult.NETWORK_ERROR, e.message ?: e.javaClass.simpleName)
                } catch (e: IllegalArgumentException) {
                    ApiResult.Error(ApiResult.NETWORK_ERROR, e.message ?: "Invalid asset URL")
                }
            }
        }
    }

    /**
     * The mod's releases, newest first, from the public GitLab project the
     * jars are published to. The LeiCraft API has no version endpoint; this
     * is the same list the release pipeline writes to.
     */
    suspend fun releases(): ApiResult<List<ApiModels.Release>> =
        when (val result = download(RELEASES_URL, MAX_RELEASES_BYTES)) {
            is ApiResult.Error -> result
            is ApiResult.Ok -> try {
                ApiResult.Ok(ApiModels.Release.readList(JsonParser.parseString(String(result.data, StandardCharsets.UTF_8))), "")
            } catch (e: Exception) {
                ApiResult.Error(200, "Malformed release list: ${e.message ?: e.javaClass.simpleName}")
            }
        }

    // -- handshake (no session yet) -------------------------------------------

    suspend fun challenge(): ApiResult<ApiModels.Challenge> =
        send(Method.POST, "/v1/client/auth/challenge", null, null, ApiModels.Challenge::read)

    suspend fun verify(challengeId: String, username: String): ApiResult<ApiModels.Verified> {
        val body = JsonObject().apply {
            addProperty("challenge_id", challengeId)
            addProperty("username", username)
        }
        return send(Method.POST, "/v1/client/auth/verify", body, null, ApiModels.Verified::read)
    }

    /**
     * Checks a stored token. Takes the token explicitly and never re-runs the
     * handshake, because [ClientAuth] calls it while deciding whether to.
     */
    suspend fun session(token: String): ApiResult<ApiModels.Session> =
        send(Method.GET, "/v1/client/session", null, token, ApiModels.Session::read)

    // -- client session ---------------------------------------------------------

    suspend fun linkStatus(): ApiResult<ApiModels.LinkStatus> =
        client(Method.GET, "/v1/client/link", null, ApiModels.LinkStatus::read)

    suspend fun createLink(): ApiResult<ApiModels.CreatedLink> =
        client(Method.POST, "/v1/client/link", null, ApiModels.CreatedLink::read)

    suspend fun unlink(): ApiResult<Unit> =
        client(Method.DELETE, "/v1/client/link", null) { }

    /**
     * One page of what the linked user can equip. [search] is left out when
     * blank: the API rejects an empty search term instead of ignoring it.
     */
    suspend fun wardrobe(
        type: String,
        search: String?,
        offset: Int,
        limit: Int = WARDROBE_PAGE_SIZE,
    ): ApiResult<List<ApiModels.WardrobeItem>> =
        client(Method.GET, wardrobePath(type, search, offset, limit), null, ApiModels.WardrobeItem::readList)

    suspend fun loadout(): ApiResult<ApiModels.Loadout> =
        client(Method.GET, "/v1/client/loadout", null, ApiModels.Loadout::read)

    suspend fun saveLoadout(update: ApiModels.LoadoutUpdate): ApiResult<ApiModels.Loadout> =
        client(Method.PUT, "/v1/client/loadout", update.toJson(), ApiModels.Loadout::read)

    /** `/v1/client/wardrobe?…`, with the query encoded. */
    fun wardrobePath(type: String, search: String?, offset: Int, limit: Int = WARDROBE_PAGE_SIZE): String {
        val query = buildList {
            add("type=${encode(type)}")
            search?.trim()?.take(MAX_SEARCH_LENGTH)?.takeIf { it.isNotEmpty() }?.let { add("search=${encode(it)}") }
            add("limit=$limit")
            add("offset=${offset.coerceAtLeast(0)}")
        }
        return "/v1/client/wardrobe?" + query.joinToString("&")
    }

    // -- plumbing -------------------------------------------------------------

    private const val LOOKUP_PATH = "/v1/players/lookup"

    private const val RELEASES_URL =
        "https://git.leicraftmc.de/api/v4/projects/LeiCraftMC%2FLeiCraftClient%2FFabric/releases?per_page=20"

    /** A page of 20 releases is a few dozen KB, most of it author and commit details. */
    private const val MAX_RELEASES_BYTES = 1024 * 1024

    /** The API caps wardrobe searches at 64 characters. */
    const val MAX_SEARCH_LENGTH = 64

    private suspend fun <T> client(
        method: Method,
        path: String,
        body: JsonElement?,
        read: (JsonElement?) -> T,
    ): ApiResult<T> {
        val token = when (val session = ClientAuth.token()) {
            is ApiResult.Ok -> session.data
            is ApiResult.Error -> return session
        }

        val first = send(method, path, body, token, read)
        if (first !is ApiResult.Error || !first.isUnauthorized) return first

        LeiCraftClient.LOGGER.debug("{} {} answered 401; signing in again", method, path)
        val fresh = ClientAuth.refreshAfterUnauthorized(token) ?: return first
        return send(method, path, body, fresh, read)
    }

    private suspend fun <T> send(
        method: Method,
        path: String,
        body: JsonElement?,
        token: String?,
        read: (JsonElement?) -> T,
    ): ApiResult<T> {
        val route = BackendGate.routeKey(method.name, path)
        val waitMillis = BackendGate.backoffRemaining(route)
        if (waitMillis > 0) {
            return ApiResult.Error(429, "Rate limited", (waitMillis + 999) / 1000)
        }

        val result = BackendGate.withPermit {
            withContext(Dispatchers.IO) { exchange(method, path, body, token, read) }
        }

        if (result is ApiResult.Error && result.isRateLimited) {
            BackendGate.backOff(route, result.retryAfterSeconds)
            LeiCraftClient.LOGGER.info("{} is rate limited for {} s", route, result.retryAfterSeconds ?: BackendGate.DEFAULT_BACKOFF_SECONDS)
        }
        return result
    }

    private fun <T> exchange(
        method: Method,
        path: String,
        body: JsonElement?,
        token: String?,
        read: (JsonElement?) -> T,
    ): ApiResult<T> {
        val url = ClientConfig.apiBaseUrl + path
        return try {
            val builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
            if (token != null) builder.header("Authorization", "Bearer $token")

            val publisher = if (body != null) {
                builder.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)
            } else {
                HttpRequest.BodyPublishers.noBody()
            }
            builder.method(method.name, publisher)

            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            val retryAfter = response.headers().firstValue("Retry-After").orElse(null)
            val result = ApiResult.parse(response.statusCode(), response.body(), retryAfter, read)
            if (result is ApiResult.Error) {
                LeiCraftClient.LOGGER.debug("{} {} -> {} {}", method, path, result.status, result.message)
            }
            result
        } catch (e: IOException) {
            LeiCraftClient.LOGGER.debug("{} {} failed", method, url, e)
            ApiResult.Error(ApiResult.NETWORK_ERROR, e.message ?: e.javaClass.simpleName)
        } catch (e: IllegalArgumentException) {
            // A malformed apiBaseUrl in the config.
            LeiCraftClient.LOGGER.warn("Invalid API URL {}", url, e)
            ApiResult.Error(ApiResult.NETWORK_ERROR, "Invalid API URL")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ApiResult.Error(ApiResult.NETWORK_ERROR, "Interrupted")
        }
    }

    /** Asset URLs are absolute; a relative one is taken against the API base. */
    private fun resolve(url: String): URI? = runCatching {
        val uri = if (url.startsWith("/")) URI.create(ClientConfig.apiBaseUrl + url) else URI.create(url)
        uri.takeIf { it.scheme == "https" || it.scheme == "http" }
    }.getOrNull()

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
}
