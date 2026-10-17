package de.leicraftmc.leicraftclient.client

import de.leicraftmc.leicraftclient.client.cape.CapeManager

/**
 * Whether a player is known to the LeiCraft backend.
 *
 * This answers "has a LeiCraft profile", which is **not** the same as "is
 * running this mod". A client cannot see what another player has installed:
 * the client brand is sent to the server and never relayed on, and custom
 * payloads need a server-side plugin to forward them. Until the V2 backend
 * ships that handshake, having a cape or a cosmetic is the closest signal that
 * can be read client-side.
 *
 * The lookup is a cache read that starts a background fetch on a miss, so it is
 * cheap enough to call while drawing the tab list.
 */
object LeiCraftUsers {

    fun isKnown(username: String): Boolean = CapeManager.capeFor(username) != null
}
