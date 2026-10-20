package de.leicraftmc.leicraftclient.client

import de.leicraftmc.leicraftclient.client.profiles.PlayerProfiles
import java.util.UUID

/**
 * Whether a player uses the LeiCraft Client -- the tab-list badge.
 *
 * Answered by the API's `uses_client` flag: true when the account signed in
 * with the mod (the session handshake) or made client-session requests within
 * the last 30 days. It does not depend on whether the player has a cape or any
 * cosmetics, nor on whether capes are switched on in the options.
 *
 * The lookup is a cache read that queues a batched profile fetch on a miss
 * (see [PlayerProfiles]), so it is cheap enough to call while drawing the tab
 * list. [name] is only used for offline-mode UUIDs.
 */
object LeiCraftUsers {

    @JvmOverloads
    fun isKnown(uuid: UUID, name: String? = null): Boolean = PlayerProfiles.usesClient(uuid, name)
}
