package de.leicraftmc.leicraftclient.client

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

/** Shared constants, the mod logger and the background scope. */
object LeiCraftClient {
    const val MOD_ID: String = "leicraftclient"

    @JvmField
    val LOGGER = LoggerFactory.getLogger("LeiCraftClient")!!

    /**
     * The mod's version as Fabric Loader sees it, e.g. `2.0.0-pre.2`.
     *
     * Falls back to `dev` outside a running loader (unit tests).
     */
    val version: String by lazy {
        runCatching {
            FabricLoader.getInstance().getModContainer(MOD_ID)
                .map { it.metadata.version.friendlyString }
                .orElse("dev")
        }.getOrDefault("dev")
    }

    /**
     * Background work (HTTP, disk, the Mojang handshake). A failed job never
     * takes the others down with it.
     */
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("leicraftclient"),
    )
}
