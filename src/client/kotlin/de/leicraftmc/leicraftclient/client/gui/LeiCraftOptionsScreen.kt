package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.assets.AssetCache
import de.leicraftmc.leicraftclient.client.auth.AccountLinking
import de.leicraftmc.leicraftclient.client.auth.ClientAuth
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import de.leicraftmc.leicraftclient.client.profiles.PlayerProfiles
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.GridLayout
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.net.URI

/**
 * Settings for the mod, reached from the pause menu.
 *
 * Toggles are written straight back to `config/leicraftclient.json` so they
 * survive a restart, and take effect on the next frame because the profile
 * lookups read [ClientConfig] every time.
 *
 * The "LeiCraft account" section mirrors [ClientAuth.state] and
 * [AccountLinking.waitingUntil]; the widgets are rebuilt whenever either
 * changes, so the buttons always match what can be done right now.
 */
class LeiCraftOptionsScreen(private val parent: Screen?) : Screen(TITLE) {

    private var shownState: Pair<ClientAuth.State, Long?>? = null

    override fun init() {
        shownState = ClientAuth.state.value to AccountLinking.waitingUntil.value

        val grid = GridLayout()
        grid.defaultCellSetting().padding(BUTTON_PADDING)
        val rows = grid.createRowHelper(COLUMNS)

        rows.addChild(StringWidget(BUTTON_WIDTH_FULL, 9, title, font), COLUMNS)

        rows.addChild(toggle(CAPES, ClientConfig.capesEnabled) {
            ClientConfig.capesEnabled = !ClientConfig.capesEnabled
            ClientConfig.save()
            rebuildWidgets()
        })
        rows.addChild(toggle(COSMETICS, ClientConfig.cosmeticsEnabled) {
            ClientConfig.cosmeticsEnabled = !ClientConfig.cosmeticsEnabled
            ClientConfig.save()
            rebuildWidgets()
        })

        rows.addChild(
            Button.builder(RELOAD) { reloadEverything() }.width(BUTTON_WIDTH_FULL).build(),
            COLUMNS,
        )

        addAccountSection(rows)

        rows.addChild(
            StringWidget(BUTTON_WIDTH_FULL, 9, Component.literal(ClientConfig.apiBaseUrl).withStyle(ChatFormatting.DARK_GRAY), font),
            COLUMNS,
        )

        rows.addChild(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }.width(BUTTON_WIDTH_FULL).build(),
            COLUMNS,
        )

        grid.arrangeElements()
        FrameLayout.alignInRectangle(grid, 0, 0, width, height, 0.5f, 0.3f)
        grid.visitWidgets { addRenderableWidget(it) }
    }

    private fun addAccountSection(rows: GridLayout.RowHelper) {
        val state = ClientAuth.state.value
        val waiting = AccountLinking.waitingUntil.value != null

        rows.addChild(StringWidget(BUTTON_WIDTH_FULL, 9, ACCOUNT_HEADER, font), COLUMNS)
        rows.addChild(
            StringWidget(BUTTON_WIDTH_FULL, 9, statusLine(state, waiting), font)
                .setMaxWidth(BUTTON_WIDTH_FULL, StringWidget.TextOverflow.SCROLLING),
            COLUMNS,
        )

        val linkButton = Button.builder(if (waiting) LINK_AGAIN else LINK) { AccountLinking.start(this) }
            .width(BUTTON_WIDTH_HALF)
            .build()
        val wardrobeButton = Button.builder(WARDROBE) { minecraft.gui.setScreen(WardrobeScreen(this)) }
            .width(BUTTON_WIDTH_HALF)
            .build()

        when (state) {
            is ClientAuth.State.Connected -> if (state.linkedUser != null) {
                rows.addChild(wardrobeButton)
                rows.addChild(Button.builder(UNLINK) { confirmUnlink() }.width(BUTTON_WIDTH_HALF).build())
            } else {
                wardrobeButton.active = false
                wardrobeButton.setTooltip(Tooltip.create(WARDROBE_NEEDS_LINK))
                rows.addChild(linkButton)
                rows.addChild(wardrobeButton)
            }
            is ClientAuth.State.Failed -> {
                rows.addChild(Button.builder(RETRY) { ClientAuth.start() }.width(BUTTON_WIDTH_HALF).build())
                wardrobeButton.active = false
                rows.addChild(wardrobeButton)
            }
            else -> {
                linkButton.active = false
                wardrobeButton.active = false
                rows.addChild(linkButton)
                rows.addChild(wardrobeButton)
            }
        }

        rows.addChild(toggle(ACCOUNT_SERVICES, ClientConfig.accountServicesEnabled) {
            ClientConfig.accountServicesEnabled = !ClientConfig.accountServicesEnabled
            ClientConfig.save()
            if (ClientConfig.accountServicesEnabled) ClientAuth.start() else ClientAuth.disable()
            rebuildWidgets()
        }.also { it.setTooltip(Tooltip.create(ACCOUNT_SERVICES_TOOLTIP)) })

        rows.addChild(
            Button.builder(WEBSITE) { _ ->
                runCatching { URI.create(ClientConfig.websiteUrl) }.getOrNull()
                    ?.let { ConfirmLinkScreen.confirmLinkNow(this, it, true) }
            }.width(BUTTON_WIDTH_HALF).build(),
        )
    }

    override fun tick() {
        super.tick()
        val now = ClientAuth.state.value to AccountLinking.waitingUntil.value
        if (now != shownState) rebuildWidgets()
    }

    private fun confirmUnlink() {
        val name = ClientAuth.linkedUser()?.displayName ?: "?"
        minecraft.gui.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) AccountLinking.unlink()
                    minecraft.gui.setScreen(this)
                },
                Component.translatable("leicraftclient.unlink.confirm.title"),
                Component.translatable("leicraftclient.unlink.confirm.message", name),
            ),
        )
    }

    private fun toggle(label: Component, on: Boolean, onPress: () -> Unit): Button =
        Button.builder(CommonComponents.optionStatus(label, on)) { onPress() }
            .width(BUTTON_WIDTH_HALF)
            .build()

    /**
     * Profiles are fetched again on their next draw. Assets are content-
     * addressed and never change, so their caches stay; only failed downloads
     * are retried.
     */
    private fun reloadEverything() {
        PlayerProfiles.invalidateAll()
        AssetCache.clearFailures()
        LeiCraftClient.LOGGER.info("Refetching player profiles from {}", ClientConfig.apiBaseUrl)
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    private companion object {
        const val COLUMNS = 2
        const val BUTTON_PADDING = 4
        const val BUTTON_WIDTH_FULL = 204
        const val BUTTON_WIDTH_HALF = 98

        val TITLE: Component = Component.translatable("leicraftclient.options.title")
        val CAPES: Component = Component.translatable("leicraftclient.options.capes")
        val COSMETICS: Component = Component.translatable("leicraftclient.options.cosmetics")
        val RELOAD: Component = Component.translatable("leicraftclient.options.reload")
        val WEBSITE: Component = Component.translatable("leicraftclient.options.website")
        val ACCOUNT_HEADER: Component = Component.translatable("leicraftclient.account.header")
        val ACCOUNT_SERVICES: Component = Component.translatable("leicraftclient.options.account_services")
        val ACCOUNT_SERVICES_TOOLTIP: Component = Component.translatable("leicraftclient.options.account_services.tooltip")
        val LINK: Component = Component.translatable("leicraftclient.account.link")
        val LINK_AGAIN: Component = Component.translatable("leicraftclient.account.link_again")
        val UNLINK: Component = Component.translatable("leicraftclient.account.unlink")
        val RETRY: Component = Component.translatable("leicraftclient.account.retry")
        val WARDROBE: Component = Component.translatable("leicraftclient.account.wardrobe")
        val WARDROBE_NEEDS_LINK: Component = Component.translatable("leicraftclient.account.wardrobe.needs_link")

        /** One line describing [state], shared with `/leicraftclient status`. */
        fun statusLine(state: ClientAuth.State, waiting: Boolean): Component = AccountStatus.describe(state, waiting)
    }
}
