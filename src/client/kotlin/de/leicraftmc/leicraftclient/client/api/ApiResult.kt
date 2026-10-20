package de.leicraftmc.leicraftclient.client.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The outcome of one API call.
 *
 * Every JSON response of the API is the envelope `{ success, code, message, data }`;
 * errors leave out `data`, and request validation failures answer
 * `{ success: false, error: [issues…] }` with status 400 instead. [parse] folds
 * all of these, plus transport failures, into [Ok] or [Error].
 */
sealed interface ApiResult<out T> {

    data class Ok<out T>(val data: T, val message: String) : ApiResult<T>

    data class Error(
        /**
         * HTTP status; [NETWORK_ERROR] when no answer arrived at all, or
         * [NOT_SIGNED_IN] when a client call could not even get a session.
         */
        val status: Int,
        /** Human-readable reason, straight from the API where it sent one. */
        val message: String,
        /** Seconds to wait before retrying, from `Retry-After` (rate limits). */
        val retryAfterSeconds: Long? = null,
    ) : ApiResult<Nothing> {
        val isNetworkError: Boolean get() = status == NETWORK_ERROR
        val isUnauthorized: Boolean get() = status == 401
        val isRateLimited: Boolean get() = status == 429
    }

    fun dataOrNull(): T? = (this as? Ok<T>)?.data

    companion object {
        /** Status used for timeouts, DNS failures and refused connections. */
        const val NETWORK_ERROR: Int = 0

        /** Status used when a client-session call has no session (disabled, offline account, sign-in failed). */
        const val NOT_SIGNED_IN: Int = -1

        /** How many validation issues are spelled out in [Error.message]. */
        private const val MAX_ISSUES = 3

        /**
         * Turns an HTTP answer into a result.
         *
         * [read] converts the envelope's `data` (which may be JSON null for
         * endpoints that answer without data); if it throws, the response is
         * reported as malformed rather than crashing the caller.
         */
        fun <T> parse(status: Int, body: String?, retryAfter: String?, read: (JsonElement?) -> T): ApiResult<T> {
            val retrySeconds = parseRetryAfter(retryAfter)
            val root = try {
                body?.takeIf { it.isNotBlank() }?.let { JsonParser.parseString(it) as? JsonObject }
            } catch (e: Exception) {
                null
            }

            if (root == null) {
                val reason = if (status in 200..299) "Malformed response" else "HTTP $status"
                return Error(status, reason, retrySeconds)
            }

            val success = runCatching { root.get("success").asBoolean }.getOrDefault(status in 200..299)
            val message = runCatching { root.get("message").asString }.getOrNull()

            if (success && status in 200..299) {
                return try {
                    Ok(read(root.get("data")), message.orEmpty())
                } catch (e: Exception) {
                    Error(status, "Malformed response: ${e.message ?: e.javaClass.simpleName}")
                }
            }

            val issues = (root.get("error") as? JsonArray)?.let(::describeIssues)
            return Error(status, message ?: issues ?: "HTTP $status", retrySeconds)
        }

        /** `Retry-After` in seconds; the HTTP-date form is not used by the API. */
        fun parseRetryAfter(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it >= 0 }

        /** `username: Must be a valid Minecraft username; …` from zod issues. */
        private fun describeIssues(issues: JsonArray): String? {
            val parts = issues.mapNotNull { element ->
                val issue = element as? JsonObject ?: return@mapNotNull null
                val text = runCatching { issue.get("message").asString }.getOrNull() ?: return@mapNotNull null
                val path = (issue.get("path") as? JsonArray)
                    ?.joinToString(".") { runCatching { it.asString }.getOrDefault("?") }
                    ?.takeIf { it.isNotEmpty() }
                if (path == null) text else "$path: $text"
            }
            if (parts.isEmpty()) return null
            val shown = parts.take(MAX_ISSUES).joinToString("; ")
            return if (parts.size > MAX_ISSUES) "$shown; …" else shown
        }
    }
}
