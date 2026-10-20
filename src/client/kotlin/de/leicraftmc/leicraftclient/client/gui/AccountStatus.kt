package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.auth.ClientAuth
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component

/** The one-line account status shown in the options screen and by `/leicraftclient status`. */
object AccountStatus {

    fun describe(state: ClientAuth.State, waitingForLink: Boolean): Component = when (state) {
        ClientAuth.State.Disabled ->
            Component.translatable("leicraftclient.account.disabled").withStyle(ChatFormatting.GRAY)
        ClientAuth.State.Offline ->
            Component.translatable("leicraftclient.account.offline").withStyle(ChatFormatting.GRAY)
        ClientAuth.State.Connecting ->
            Component.translatable("leicraftclient.account.connecting").withStyle(ChatFormatting.YELLOW)
        is ClientAuth.State.Failed ->
            Component.translatable("leicraftclient.account.error", state.reason).withStyle(ChatFormatting.RED)
        is ClientAuth.State.Connected -> when {
            state.linkedUser != null ->
                Component.translatable("leicraftclient.account.linked", state.linkedUser.displayName).withStyle(ChatFormatting.GREEN)
            waitingForLink ->
                Component.translatable("leicraftclient.account.waiting").withStyle(ChatFormatting.YELLOW)
            else ->
                Component.translatable("leicraftclient.account.not_linked").withStyle(ChatFormatting.GRAY)
        }
    }
}
