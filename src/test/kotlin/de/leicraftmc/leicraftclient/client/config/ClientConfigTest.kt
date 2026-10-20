package de.leicraftmc.leicraftclient.client.config

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientConfigTest {

    private fun snapshot(json: String) = ClientConfig.Snapshot.fromJson(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `a V1 config is moved to the V2 API`() {
        val migrated = snapshot(
            """{"apiBaseUrl":"https://capesapi.leicraftmc.de","capesEnabled":false,"cosmeticsEnabled":true,"cacheSeconds":120}""",
        )
        assertEquals(ClientConfig.DEFAULT_API_BASE, migrated.apiBaseUrl)
        assertTrue(migrated.migrated, "the file is written back")
        // Everything the user chose is kept; new keys get their defaults.
        assertFalse(migrated.capesEnabled)
        assertEquals(120L, migrated.cacheSeconds)
        assertEquals(ClientConfig.DEFAULT_WEBSITE, migrated.websiteUrl)
        assertTrue(migrated.accountServicesEnabled)
    }

    @Test
    fun `the old URL is recognised in any spelling`() {
        for (old in listOf("https://capesapi.leicraftmc.de/", "HTTPS://CapesAPI.leicraftmc.de", " http://capesapi.leicraftmc.de ")) {
            assertEquals(ClientConfig.DEFAULT_API_BASE, ClientConfig.migrateApiBaseUrl(old), old)
        }
    }

    @Test
    fun `a custom API is kept, without a trailing slash`() {
        assertEquals("http://localhost:12551", ClientConfig.migrateApiBaseUrl("http://localhost:12551/"))
        assertEquals(ClientConfig.DEFAULT_API_BASE, ClientConfig.migrateApiBaseUrl(null))
        assertEquals(ClientConfig.DEFAULT_API_BASE, ClientConfig.migrateApiBaseUrl("  "))
    }

    @Test
    fun `a current config needs no rewrite`() {
        val current = snapshot(
            """{"apiBaseUrl":"https://api.client.leicraftmc.de","websiteUrl":"https://client.leicraftmc.de",
               "capesEnabled":true,"cosmeticsEnabled":true,"accountServicesEnabled":false,"cacheSeconds":600}""",
        )
        assertFalse(current.migrated)
        assertFalse(current.accountServicesEnabled)
    }

    @Test
    fun `bad values fall back to defaults`() {
        val lenient = snapshot("""{"capesEnabled":{},"cacheSeconds":5,"websiteUrl":"https://example.org/"}""")
        assertTrue(lenient.capesEnabled)
        assertEquals(ClientConfig.MIN_CACHE_SECONDS, lenient.cacheSeconds)
        assertEquals("https://example.org", lenient.websiteUrl)
        assertEquals(ClientConfig.DEFAULT_API_BASE, lenient.apiBaseUrl)
        assertTrue(lenient.migrated, "missing keys are written back")
    }

    @Test
    fun `snapshots round-trip`() {
        val original = ClientConfig.Snapshot(apiBaseUrl = "http://localhost:12551", cacheSeconds = 90, cosmeticsEnabled = false)
        val reread = ClientConfig.Snapshot.fromJson(original.toJson())
        assertEquals(original, reread)
    }
}
