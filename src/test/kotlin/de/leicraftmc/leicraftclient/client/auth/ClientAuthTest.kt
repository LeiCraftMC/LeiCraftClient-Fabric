package de.leicraftmc.leicraftclient.client.auth

import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientAuthTest {

    private val online = UUID.fromString("4566e69f-c907-48ee-8d71-d7ba5aa00d20")

    @Test
    fun `only real Microsoft accounts do the handshake`() {
        assertTrue(ClientAuth.isOnlineAccount(online, "eyJhbGciOi.some.jwt", offlineDeveloperMode = false))

        // Dev and offline launches: name-based UUID, placeholder token.
        val offline = UUID.nameUUIDFromBytes("OfflinePlayer:Player123".toByteArray(StandardCharsets.UTF_8))
        assertFalse(ClientAuth.isOnlineAccount(offline, "eyJhbGciOi.some.jwt", offlineDeveloperMode = false))
        for (token in listOf(null, "", " ", "0", "FabricMC")) {
            assertFalse(ClientAuth.isOnlineAccount(online, token, offlineDeveloperMode = false), "token '$token'")
        }
        assertFalse(ClientAuth.isOnlineAccount(online, "eyJhbGciOi.some.jwt", offlineDeveloperMode = true))
    }

    @Test
    fun `stored sessions round-trip`() {
        val stored = ClientAuth.StoredSession("lcc_client_abc:def", 1_793_094_950_484L, online)
        assertEquals(stored, ClientAuth.StoredSession.fromJson(stored.toJson()))
    }

    @Test
    fun `broken session files are ignored`() {
        assertNull(ClientAuth.StoredSession.fromJson(""))
        assertNull(ClientAuth.StoredSession.fromJson("{}"))
        assertNull(ClientAuth.StoredSession.fromJson("""{"token":"","expires_at":1,"uuid":"$online"}"""))
        assertNull(ClientAuth.StoredSession.fromJson("""{"token":"t","expires_at":1,"uuid":"nope"}"""))
    }

    @Test
    fun `a stored session is reused only for the same account before it expires`() {
        val now = 1_000_000_000L
        val stored = ClientAuth.StoredSession("t", now + 7L * 24 * 3600 * 1000, online)
        assertTrue(stored.isUsableFor(online, now))
        assertFalse(stored.isUsableFor(UUID.randomUUID(), now), "another account signed in")
        assertFalse(stored.isUsableFor(online, stored.expiresAt), "expired")
        assertFalse(stored.isUsableFor(online, stored.expiresAt - 30_000), "about to expire")
    }
}
