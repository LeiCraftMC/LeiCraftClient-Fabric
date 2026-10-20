package de.leicraftmc.leicraftclient.client.command

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import de.leicraftmc.leicraftclient.client.auth.AccountLinking
import de.leicraftmc.leicraftclient.client.auth.ClientAuth
import de.leicraftmc.leicraftclient.client.gui.AccountStatus
import de.leicraftmc.leicraftclient.client.gui.Notices
import de.leicraftmc.leicraftclient.client.gui.WardrobeScreen
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

/**
 * `/leicraftclient` -- client-side only, never sent to the server.
 *
 * - `status`   the sign-in and link state
 * - `link`     link this Minecraft account (prints a clickable link)
 * - `wardrobe` open the wardrobe (needs a linked account)
 */
object LeiCraftCommands {

    private const val ROOT = "leicraftclient"

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal(ROOT)
                    .executes(::status)
                    .then(ClientCommands.literal("status").executes(::status))
                    .then(ClientCommands.literal("link").executes(::link))
                    .then(ClientCommands.literal("wardrobe").executes(::wardrobe)),
            )
        }
    }

    private fun status(context: CommandContext<FabricClientCommandSource>): Int {
        val state = ClientAuth.state.value
        val line = AccountStatus.describe(state, AccountLinking.waitingUntil.value != null)
        context.source.sendFeedback(Component.translatable("leicraftclient.command.status", line))
        if (state is ClientAuth.State.Connected) {
            context.source.sendFeedback(
                Component.translatable("leicraftclient.command.status.account", state.account.name, state.account.uuid.toString()),
            )
        }
        return Command.SINGLE_SUCCESS
    }

    private fun link(context: CommandContext<FabricClientCommandSource>): Int {
        when (val state = ClientAuth.state.value) {
            ClientAuth.State.Disabled -> return fail(context, "leicraftclient.command.disabled")
            ClientAuth.State.Offline -> return fail(context, "leicraftclient.account.offline")
            is ClientAuth.State.Connected -> if (state.linkedUser != null) {
                context.source.sendFeedback(Component.translatable("leicraftclient.account.linked", state.linkedUser.displayName))
                return Command.SINGLE_SUCCESS
            }
            else -> Unit
        }
        context.source.sendFeedback(Component.translatable("leicraftclient.command.link.creating"))
        // Signs in first if needed; the link arrives in chat.
        AccountLinking.start(null)
        return Command.SINGLE_SUCCESS
    }

    private fun wardrobe(context: CommandContext<FabricClientCommandSource>): Int {
        val state = ClientAuth.state.value
        if (state !is ClientAuth.State.Connected || state.linkedUser == null) {
            return fail(context, "leicraftclient.command.wardrobe.needs_link")
        }
        // The chat screen closes right after the command; open on the next tick.
        Notices.nextTick { context.source.client.gui.setScreen(WardrobeScreen(null)) }
        return Command.SINGLE_SUCCESS
    }

    private fun fail(context: CommandContext<FabricClientCommandSource>, key: String): Int {
        context.source.sendError(Component.translatable(key))
        return 0
    }
}
