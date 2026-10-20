package de.leicraftmc.leicraftclient.client.profiles

import de.leicraftmc.leicraftclient.client.api.ApiModels
import java.util.Locale
import java.util.UUID

/**
 * How queued players become `POST /v1/players/lookup` requests, and how the
 * answers map back. Pure logic, so it is tested on a plain JVM.
 *
 * Players are looked up by UUID. A version 3 UUID is the name-based UUID an
 * offline-mode server makes up, which the API cannot know, so those players
 * fall back to the `names` field (matched against the name the account last
 * verified with). Anything else (NPCs with version 2 UUIDs, …) is not sent.
 */
object LookupBatch {

    const val MAX_PER_REQUEST: Int = 100

    /** Minecraft's name rule, as the API validates it; one bad name fails the whole request. */
    private val NAME = Regex("^\\w{3,16}$")

    enum class Mode { BY_UUID, BY_NAME, SKIP }

    /** A player waiting for a lookup: their UUID as the game knows it, and their name. */
    data class Key(val uuid: UUID, val name: String?)

    /** One request; [keys] are the players it answers. */
    data class Request(val keys: List<Key>, val uuids: List<String>, val names: List<String>)

    fun modeFor(uuid: UUID, name: String?): Mode = when {
        uuid.version() == 4 -> Mode.BY_UUID
        uuid.version() == 3 && name != null && NAME.matches(name) -> Mode.BY_NAME
        else -> Mode.SKIP
    }

    /**
     * Splits [keys] into requests of at most [maxPerRequest] players. Duplicate
     * UUIDs, and names that differ only in case, are sent once; every key still
     * gets its answer through [match]. [Mode.SKIP] keys are left out.
     */
    fun plan(keys: Collection<Key>, maxPerRequest: Int = MAX_PER_REQUEST): List<Request> {
        require(maxPerRequest > 0)
        val requests = mutableListOf<Request>()

        var batchKeys = mutableListOf<Key>()
        var uuids = LinkedHashSet<String>()
        var names = LinkedHashSet<String>()

        for (key in keys.distinctBy { it.uuid }) {
            val mode = modeFor(key.uuid, key.name)
            if (mode == Mode.SKIP) continue

            val uuidValue = key.uuid.toString()
            val nameValue = key.name?.lowercase(Locale.ROOT)
            val isNew = when (mode) {
                Mode.BY_UUID -> uuidValue !in uuids
                else -> nameValue !in names
            }

            if (isNew && uuids.size + names.size >= maxPerRequest) {
                requests += Request(batchKeys, uuids.toList(), names.toList())
                batchKeys = mutableListOf()
                uuids = LinkedHashSet()
                names = LinkedHashSet()
            }

            when (mode) {
                Mode.BY_UUID -> uuids += uuidValue
                else -> names += nameValue!!
            }
            batchKeys += key
        }

        if (batchKeys.isNotEmpty()) {
            requests += Request(batchKeys, uuids.toList(), names.toList())
        }
        return requests
    }

    /**
     * The profile for each key of [request], or null for "no profile" (the API
     * leaves those players out of its answer).
     */
    fun match(request: Request, profiles: List<ApiModels.PlayerProfile>): Map<UUID, ApiModels.PlayerProfile?> {
        val byUuid = profiles.associateBy { it.uuid }
        val byName = profiles.associateBy { it.name.lowercase(Locale.ROOT) }
        return request.keys.associate { key ->
            key.uuid to when (modeFor(key.uuid, key.name)) {
                Mode.BY_UUID -> byUuid[key.uuid]
                Mode.BY_NAME -> byName[key.name!!.lowercase(Locale.ROOT)]
                Mode.SKIP -> null
            }
        }
    }
}
