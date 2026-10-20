package de.leicraftmc.leicraftclient.client

import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.api.ApiResult

/**
 * Real API responses, captured from the API-Server (`src/test/resources/api`),
 * and real stored assets (`src/test/resources/assets`).
 */
object Fixtures {

    fun text(path: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$path")) { "missing fixture $path" }
            .bufferedReader(Charsets.UTF_8).readText()

    fun bytes(path: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/$path")) { "missing fixture $path" }.readBytes()

    /** The response `api/<name>.json` with the HTTP status it was captured with. */
    fun api(name: String): Pair<Int, String> {
        val body = text("api/$name.json")
        val root = JsonParser.parseString(body).asJsonObject
        // Validation errors carry no `code`; the API sends them as 400.
        val status = root.get("code")?.asInt ?: 400
        return status to body
    }

    /** Parses `api/<name>.json` as the API client would. */
    fun <T> parse(name: String, retryAfter: String? = null, read: (com.google.gson.JsonElement?) -> T): ApiResult<T> {
        val (status, body) = api(name)
        return ApiResult.parse(status, body, retryAfter, read)
    }

    fun <T> ok(name: String, read: (com.google.gson.JsonElement?) -> T): T {
        val result = parse(name, read = read)
        check(result is ApiResult.Ok) { "$name did not parse: $result" }
        return result.data
    }
}
