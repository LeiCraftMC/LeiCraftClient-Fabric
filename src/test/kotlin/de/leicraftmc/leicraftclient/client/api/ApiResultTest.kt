package de.leicraftmc.leicraftclient.client.api

import de.leicraftmc.leicraftclient.client.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `{ success, code, message, data }` envelope and every error shape the API sends. */
class ApiResultTest {

    @Test
    fun `success unwraps data and message`() {
        val result = Fixtures.parse("challenge", read = ApiModels.Challenge::read)
        val ok = assertIs<ApiResult.Ok<ApiModels.Challenge>>(result)
        assertEquals("Challenge created", ok.message)
        assertEquals(40, ok.data.serverId.length, "a vanilla server hash is 40 hex chars")
    }

    @Test
    fun `success without data reads JSON null`() {
        val result = Fixtures.parse("unlink") { data ->
            assertTrue(data == null || data.isJsonNull)
        }
        assertIs<ApiResult.Ok<Unit>>(result)
    }

    @Test
    fun `errors keep the status and the API's message`() {
        val forbidden = assertIs<ApiResult.Error>(Fixtures.parse("loadout-error-403", read = ApiModels.Loadout::read))
        assertEquals(403, forbidden.status)
        assertEquals("You do not own product 4", forbidden.message)

        val unauthorized = assertIs<ApiResult.Error>(Fixtures.parse("client-error-401", read = ApiModels.Session::read))
        assertTrue(unauthorized.isUnauthorized)
        assertEquals("Invalid or expired token", unauthorized.message)

        val notLinked = assertIs<ApiResult.Error>(Fixtures.parse("wardrobe-error-not-linked", read = ApiModels.WardrobeItem::readList))
        assertEquals(400, notLinked.status)
        assertEquals("This Minecraft account is not linked", notLinked.message)

        val conflict = assertIs<ApiResult.Error>(Fixtures.parse("link-create-error-409", read = ApiModels.CreatedLink::read))
        assertEquals(409, conflict.status)
    }

    @Test
    fun `zod validation errors are summarised from their issues`() {
        val error = assertIs<ApiResult.Error>(Fixtures.parse("verify-error-validation", read = ApiModels.Verified::read))
        assertEquals(400, error.status)
        assertEquals(
            "challenge_id: Too small: expected string to have >=1 characters; username: Must be a valid Minecraft username",
            error.message,
        )

        val lookup = assertIs<ApiResult.Error>(Fixtures.parse("lookup-error-validation", read = ApiModels.PlayerProfile::readList))
        assertEquals("uuids.0: Must be a Minecraft UUID", lookup.message)
    }

    @Test
    fun `rate limits carry Retry-After`() {
        val error = assertIs<ApiResult.Error>(Fixtures.parse("link-create-error-429", retryAfter = "594", read = ApiModels.CreatedLink::read))
        assertTrue(error.isRateLimited)
        assertEquals(594L, error.retryAfterSeconds)
        assertEquals("Too many requests. Try again later.", error.message)

        assertNull(ApiResult.parseRetryAfter(null))
        assertNull(ApiResult.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(ApiResult.parseRetryAfter("-5"))
        assertEquals(30L, ApiResult.parseRetryAfter(" 30 "))
    }

    @Test
    fun `non-JSON bodies become an error with the status`() {
        val gateway = assertIs<ApiResult.Error>(ApiResult.parse(502, "<html>Bad Gateway</html>", null) { it })
        assertEquals(502, gateway.status)
        assertEquals("HTTP 502", gateway.message)

        val empty = assertIs<ApiResult.Error>(ApiResult.parse(200, "", null) { it })
        assertEquals("Malformed response", empty.message)
    }

    @Test
    fun `a data shape the reader rejects is an error, not an exception`() {
        // The session fixture has no "token": reading it as a verify response must fail cleanly.
        val (status, body) = Fixtures.api("session")
        val error = assertIs<ApiResult.Error>(ApiResult.parse(status, body, null, ApiModels.Verified::read))
        assertEquals(200, error.status)
        assertTrue(error.message.startsWith("Malformed response"), error.message)
    }

    @Test
    fun `rate-limit back-off is per route`() {
        val lookup = BackendGate.routeKey("POST", "/v1/players/lookup")
        val link = BackendGate.routeKey("POST", "/v1/client/link?x=1")
        assertEquals("POST /v1/client/link", link)

        val now = 1_000_000L
        BackendGate.backOff(link, 10, now)
        assertEquals(10_000L, BackendGate.backoffRemaining(link, now))
        assertEquals(0L, BackendGate.backoffRemaining(lookup, now))
        assertEquals(0L, BackendGate.backoffRemaining(link, now + 10_000))
    }
}
