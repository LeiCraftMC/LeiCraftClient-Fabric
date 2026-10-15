package de.leicraftmc.leicraftclient.client

import org.slf4j.LoggerFactory

/** Shared constants and the mod logger. */
object LeiCraftClient {
    const val MOD_ID: String = "leicraftclient"

    @JvmField
    val LOGGER = LoggerFactory.getLogger("LeiCraftClient")!!
}
