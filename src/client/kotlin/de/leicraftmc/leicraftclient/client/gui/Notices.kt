package de.leicraftmc.leicraftclient.client.gui

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Chat lines, toasts and screen changes from anywhere, including background
 * threads: everything is handed to the render thread first.
 */
object Notices {

    private val TOAST = SystemToast.SystemToastId()

    private val PREFIX: Component = Component.literal("[LeiCraft] ").withStyle(ChatFormatting.AQUA)

    private val pendingScreen = AtomicReference<Runnable?>(null)

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ -> pendingScreen.getAndSet(null)?.run() }
    }

    /** A client-side chat line, prefixed so it is clearly from the mod. */
    fun chat(message: Component) {
        val minecraft = Minecraft.getInstance()
        minecraft.execute {
            minecraft.gui.chatListener().handleSystemMessage(Component.empty().append(PREFIX).append(message), false)
        }
    }

    fun toast(title: Component, message: Component?) {
        val minecraft = Minecraft.getInstance()
        minecraft.execute { SystemToast.addOrUpdate(minecraft.gui.toastManager(), TOAST, title, message) }
    }

    /**
     * Runs [action] at the end of the next client tick. Needed for screens
     * opened from a chat command: the chat screen closes itself right after the
     * command runs, which would replace a screen opened immediately.
     */
    fun nextTick(action: Runnable) {
        pendingScreen.set(action)
    }

    /** [url] as a clickable, underlined chat link. */
    fun link(url: String): MutableComponent {
        val uri = runCatching { URI.create(url) }.getOrNull()
        var style = Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
        if (uri != null) {
            style = style
                .withClickEvent(ClickEvent.OpenUrl(uri))
                .withHoverEvent(HoverEvent.ShowText(Component.translatable("leicraftclient.link.hover")))
        }
        return Component.literal(url).withStyle(style)
    }
}
