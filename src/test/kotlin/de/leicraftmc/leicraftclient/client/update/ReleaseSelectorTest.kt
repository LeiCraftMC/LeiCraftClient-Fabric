package de.leicraftmc.leicraftclient.client.update

import com.google.gson.JsonParser
import de.leicraftmc.leicraftclient.client.Fixtures
import de.leicraftmc.leicraftclient.client.api.ApiModels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `gitlab/releases.json` is the real answer of the Fabric project's
 * `GET /api/v4/projects/:id/releases`, unedited.
 */
class ReleaseSelectorTest {

    private fun release(version: String) = ApiModels.Release(version, "https://example.invalid/$version")

    @Test
    fun `reads tags without the v and the release page`() {
        val releases = ApiModels.Release.readList(JsonParser.parseString(Fixtures.text("gitlab/releases.json")))

        assertEquals(listOf("2.0.0-pre.2", "2.0.0-pre.1"), releases.map { it.version })
        assertEquals("https://git.leicraftmc.de/LeiCraftMC/LeiCraftClient/Fabric/-/releases/v2.0.0-pre.2", releases.first().url)
    }

    @Test
    fun `skips upcoming releases`() {
        val json = """[
            {"tag_name": "v3.0.0", "upcoming_release": true, "_links": {"self": "https://example.invalid/3"}},
            {"tag_name": "v2.0.0", "upcoming_release": false, "_links": {"self": "https://example.invalid/2"}}
        ]"""
        assertEquals(listOf("2.0.0"), ApiModels.Release.readList(JsonParser.parseString(json)).map { it.version })
    }

    @Test
    fun `offers the newest release above the running one`() {
        val releases = ApiModels.Release.readList(JsonParser.parseString(Fixtures.text("gitlab/releases.json")))

        assertEquals("2.0.0-pre.2", ReleaseSelector.newer("2.0.0-pre.1", releases)?.version)
        assertNull(ReleaseSelector.newer("2.0.0-pre.2", releases), "already on the newest")
        assertNull(ReleaseSelector.newer("2.0.0-pre.3", releases), "a local build ahead of the releases")
    }

    @Test
    fun `pre-release numbers compare as numbers`() {
        val releases = listOf(release("2.0.0-pre.9"), release("2.0.0-pre.10"))
        assertEquals("2.0.0-pre.10", ReleaseSelector.newer("2.0.0-pre.2", releases)?.version)
    }

    @Test
    fun `a stable version is only offered stable releases`() {
        val releases = listOf(release("2.1.0-pre.1"), release("2.0.1"))

        assertEquals("2.0.1", ReleaseSelector.newer("2.0.0", releases)?.version)
        assertNull(ReleaseSelector.newer("2.0.1", releases))
        assertEquals("2.1.0-pre.1", ReleaseSelector.newer("2.0.1-pre.1", releases)?.version, "a pre-release takes pre-releases")
    }

    @Test
    fun `the final release beats its pre-releases`() {
        assertEquals("2.0.0", ReleaseSelector.newer("2.0.0-pre.3", listOf(release("2.0.0"), release("2.0.0-pre.4")))?.version)
    }

    @Test
    fun `unparsable versions offer and are offered nothing`() {
        assertNull(ReleaseSelector.newer("dev", listOf(release("9.9.9"))))
        assertNull(ReleaseSelector.newer("2.0.0", listOf(release("latest"))))
    }
}
