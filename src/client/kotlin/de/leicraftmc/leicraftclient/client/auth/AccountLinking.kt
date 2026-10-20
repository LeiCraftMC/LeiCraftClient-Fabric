package de.leicraftmc.leicraftclient.client.auth

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.api.ApiResult
import de.leicraftmc.leicraftclient.client.api.BackendGate
import de.leicraftmc.leicraftclient.client.api.LeiCraftApi
import de.leicraftmc.leicraftclient.client.gui.Notices
import de.leicraftmc.leicraftclient.client.profiles.OwnPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * Links the signed-in Minecraft account to a LeiCraft account.
 *
 * `POST /v1/client/link` returns a one-time URL (valid 10 minutes). The player
 * opens it -- through [ConfirmLinkScreen], or later from the clickable chat
 * line -- logs in with Discord on the website and confirms. Meanwhile
 * `GET /v1/client/link` is polled every [POLL_INTERVAL_MS] until it reports
 * `linked` or the code expires.
 */
object AccountLinking {

    private const val POLL_INTERVAL_MS = 3_000L

    private val mutableWaitingUntil = MutableStateFlow<Long?>(null)

    /** Expiry of the open link while waiting for the website confirmation, else null. */
    val waitingUntil: StateFlow<Long?> = mutableWaitingUntil.asStateFlow()

    private val generation = AtomicInteger()

    @Volatile
    private var pollJob: Job? = null

    /**
     * Creates a link, shows it, and waits for the confirmation. [parent] is the
     * screen to return to from the confirm-link screen; null when started from
     * a chat command.
     */
    fun start(parent: Screen?) {
        LeiCraftClient.scope.launch {
            when (val result = LeiCraftApi.createLink()) {
                is ApiResult.Ok -> onLinkCreated(result.data, parent)
                is ApiResult.Error -> when (result.status) {
                    409 -> {
                        refreshStatus()
                        Notices.chat(Component.translatable("leicraftclient.link.already"))
                    }
                    429 -> Notices.chat(
                        Component.translatable("leicraftclient.error.rate_limited", result.retryAfterSeconds ?: BackendGate.DEFAULT_BACKOFF_SECONDS),
                    )
                    else -> Notices.chat(Component.translatable("leicraftclient.link.failed", result.message))
                }
            }
        }
    }

    /** `DELETE /v1/client/link`; the API clears the loadout along with the link. */
    fun unlink() {
        LeiCraftClient.scope.launch {
            when (val result = LeiCraftApi.unlink()) {
                is ApiResult.Ok -> {
                    ClientAuth.updateLinkedUser(null)
                    OwnPlayer.refreshProfile()
                    Notices.toast(Component.translatable("leicraftclient.unlink.done"), null)
                }
                is ApiResult.Error -> Notices.toast(
                    Component.translatable("leicraftclient.unlink.failed"),
                    Component.literal(result.message),
                )
            }
        }
    }

    /** Re-reads the link status, e.g. after a `409 already linked`. */
    suspend fun refreshStatus() {
        val status = LeiCraftApi.linkStatus().dataOrNull() ?: return
        ClientAuth.updateLinkedUser(if (status.linked) status.user else null)
    }

    private fun onLinkCreated(link: ApiModels.CreatedLink, parent: Screen?) {
        Notices.chat(Component.translatable("leicraftclient.link.chat", Notices.link(link.url)))

        val uri = runCatching { URI.create(link.url) }.getOrNull()
        if (uri != null) {
            val minecraft = Minecraft.getInstance()
            minecraft.execute {
                // Only take over the screen the player started from; if they
                // moved on in the meantime, the chat link is enough.
                val current = minecraft.gui.screen()
                if (current === parent || (parent == null && current == null)) {
                    ConfirmLinkScreen.confirmLinkNow(parent, uri, true)
                }
            }
        }

        poll(link)
    }

    private fun poll(link: ApiModels.CreatedLink) {
        pollJob?.cancel()
        val myGeneration = generation.incrementAndGet()
        mutableWaitingUntil.value = link.expiresAt

        pollJob = LeiCraftClient.scope.launch {
            try {
                while (isActive && System.currentTimeMillis() < link.expiresAt) {
                    delay(POLL_INTERVAL_MS)
                    when (val result = LeiCraftApi.linkStatus()) {
                        is ApiResult.Ok -> if (result.data.linked) {
                            onLinked(result.data.user)
                            return@launch
                        }
                        is ApiResult.Error -> if (result.isRateLimited) {
                            delay((result.retryAfterSeconds ?: BackendGate.DEFAULT_BACKOFF_SECONDS) * 1000)
                        }
                    }
                }
                if (isActive) Notices.chat(Component.translatable("leicraftclient.link.expired"))
            } finally {
                if (generation.get() == myGeneration) mutableWaitingUntil.value = null
            }
        }
    }

    private fun onLinked(user: ApiModels.PublicUser?) {
        ClientAuth.updateLinkedUser(user)
        OwnPlayer.refreshProfile()
        val name = user?.displayName ?: "?"
        Notices.toast(Component.translatable("leicraftclient.link.done.title"), Component.translatable("leicraftclient.link.done", name))
        Notices.chat(Component.translatable("leicraftclient.link.done", name))
        LeiCraftClient.LOGGER.info("Minecraft account linked to LeiCraft user {}", name)
    }
}
