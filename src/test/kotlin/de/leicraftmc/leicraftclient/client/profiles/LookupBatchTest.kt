package de.leicraftmc.leicraftclient.client.profiles

import de.leicraftmc.leicraftclient.client.Fixtures
import de.leicraftmc.leicraftclient.client.api.ApiModels
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LookupBatchTest {

    /** Offline-mode servers derive this from the name (`UUIDUtil.createOfflinePlayerUUID`). */
    private fun offlineUuid(name: String): UUID =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(StandardCharsets.UTF_8))

    private fun onlineKeys(count: Int) = List(count) { LookupBatch.Key(UUID.randomUUID(), "Player$it") }

    @Test
    fun `uuids are split into requests of at most 100`() {
        val keys = onlineKeys(250)
        val requests = LookupBatch.plan(keys)
        assertEquals(listOf(100, 100, 50), requests.map { it.uuids.size })
        assertTrue(requests.all { it.names.isEmpty() })
        assertEquals(keys.map { it.uuid.toString() }, requests.flatMap { it.uuids })
        assertEquals(keys, requests.flatMap { it.keys })
    }

    @Test
    fun `duplicates are sent once`() {
        val keys = onlineKeys(3)
        val requests = LookupBatch.plan(keys + keys + keys.reversed())
        val request = requests.single()
        assertEquals(keys.map { it.uuid.toString() }, request.uuids)
        assertEquals(3, request.keys.size)
    }

    @Test
    fun `offline-mode uuids are looked up by name, case-insensitively once`() {
        val steve = LookupBatch.Key(offlineUuid("Steve"), "Steve")
        val online = LookupBatch.Key(UUID.randomUUID(), "Alex")
        assertEquals(LookupBatch.Mode.BY_NAME, LookupBatch.modeFor(steve.uuid, steve.name))

        val request = LookupBatch.plan(listOf(steve, online)).single()
        assertEquals(listOf(online.uuid.toString()), request.uuids)
        assertEquals(listOf("steve"), request.names)

        // Two offline players whose names differ only in case share one name slot.
        val other = LookupBatch.Key(offlineUuid("STEVE"), "STEVE")
        assertEquals(listOf("steve"), LookupBatch.plan(listOf(steve, other)).single().names)
    }

    @Test
    fun `names and uuids together stay within the limit`() {
        val keys = onlineKeys(60) + List(60) { LookupBatch.Key(offlineUuid("Off$it"), "Off$it") }
        val requests = LookupBatch.plan(keys)
        assertEquals(listOf(100, 20), requests.map { it.uuids.size + it.names.size })
    }

    @Test
    fun `unusable players are not sent`() {
        val npc = UUID.fromString("a1b2c3d4-0000-2000-8000-000000000001") // version 2, e.g. Citizens NPCs
        val badName = LookupBatch.Key(offlineUuid("bad name!"), "bad name!")
        val nameless = LookupBatch.Key(offlineUuid("x"), null)

        assertEquals(LookupBatch.Mode.SKIP, LookupBatch.modeFor(npc, "Npc"))
        assertEquals(LookupBatch.Mode.SKIP, LookupBatch.modeFor(badName.uuid, badName.name))
        assertTrue(LookupBatch.plan(listOf(LookupBatch.Key(npc, "Npc"), badName, nameless)).isEmpty())
    }

    @Test
    fun `answers map back to every asked player, missing ones as no profile`() {
        val steve = UUID.fromString("4566e69f-c907-48ee-8d71-d7ba5aa00d20")
        val alex = UUID.fromString("0f3b1c2d-6e5f-4a1b-9c8d-7e6f5a4b3c2d")
        val unknown = UUID.fromString("11111111-2222-4333-8444-555555555555")
        val request = LookupBatch.plan(listOf(steve, alex, unknown).map { LookupBatch.Key(it, null) }).single()

        val matched = LookupBatch.match(request, Fixtures.ok("lookup", ApiModels.PlayerProfile::readList))
        assertEquals(setOf(steve, alex, unknown), matched.keys)
        assertEquals("LeiCraft_Steve", matched[steve]?.name)
        assertEquals("Alex_Other", matched[alex]?.name)
        assertNull(matched[unknown])
    }

    @Test
    fun `name lookups map back to the offline uuid`() {
        val offline = offlineUuid("leicraft_steve")
        val request = LookupBatch.plan(listOf(LookupBatch.Key(offline, "leicraft_steve"))).single()
        assertEquals(listOf("leicraft_steve"), request.names)

        val matched = LookupBatch.match(request, Fixtures.ok("lookup-by-name", ApiModels.PlayerProfile::readList))
        assertEquals("LeiCraft_Steve", matched[offline]?.name)
    }
}
