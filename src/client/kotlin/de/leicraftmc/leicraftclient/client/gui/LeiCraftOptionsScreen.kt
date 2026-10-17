package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.TextureStore
import de.leicraftmc.leicraftclient.client.cape.CapeManager
import de.leicraftmc.leicraftclient.client.config.ClientConfig
import de.leicraftmc.leicraftclient.client.cosmetics.CosmeticsManager
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.GridLayout
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.net.URI

/**
 * Settings for the mod, reached from the pause menu.
 *
 * Toggles are written straight back to `config/leicraftclient.json` so they
 * survive a restart, and take effect on the next frame because the managers
 * read [ClientConfig] on every lookup.
 */
class LeiCraftOptionsScreen(private val parent: Screen?) : Screen(TITLE) {

    private var reloadButton: Button? = null

    override fun init() {
        val grid = GridLayout()
        grid.defaultCellSetting().padding(BUTTON_PADDING)
        val rows = grid.createRowHelper(COLUMNS)

        rows.addChild(StringWidget(BUTTON_WIDTH_FULL, 9, title, font), COLUMNS)

        rows.addChild(toggle(CAPES, ClientConfig.capesEnabled) {
            ClientConfig.capesEnabled = !ClientConfig.capesEnabled
            ClientConfig.save()
            // Re-read so the label flips and a disabled cape disappears at once.
            CapeManager.invalidateAll()
            rebuild()
        })
        rows.addChild(toggle(COSMETICS, ClientConfig.cosmeticsEnabled) {
            ClientConfig.cosmeticsEnabled = !ClientConfig.cosmeticsEnabled
            ClientConfig.save()
            CosmeticsManager.invalidateAll()
            rebuild()
        })

        reloadButton = rows.addChild(
            Button.builder(RELOAD) { button ->
                button.active = false
                button.message = RELOADING
                reloadEverything()
                button.active = true
                button.message = RELOAD
            }.width(BUTTON_WIDTH_FULL).build(),
            COLUMNS,
        )

        rows.addChild(
            Button.builder(WEBSITE, ConfirmLinkScreen.confirmLink(this, URI.create(WEBSITE_URL)))
                .width(BUTTON_WIDTH_FULL)
                .build(),
            COLUMNS,
        )

        rows.addChild(
            StringWidget(BUTTON_WIDTH_FULL, 9, Component.literal(ClientConfig.apiBaseUrl), font),
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

    private fun toggle(label: Component, on: Boolean, onPress: () -> Unit): Button =
        Button.builder(CommonComponents.optionStatus(label, on)) { onPress() }
            .width(BUTTON_WIDTH_HALF)
            .build()

    /** Cheapest way to refresh the toggle labels after a change. */
    private fun rebuild() {
        rebuildWidgets()
    }

    /**
     * Drops every cached profile, model and texture registration so the next
     * frame refetches capes and cosmetics from the backend.
     */
    private fun reloadEverything() {
        CapeManager.invalidateAll()
        CosmeticsManager.invalidateAll()
        TextureStore.clear()
        LeiCraftClient.LOGGER.info("Cleared cape and cosmetic caches; refetching from {}", ClientConfig.apiBaseUrl)
    }

    override fun onClose() {
        minecraft?.gui?.setScreen(parent)
    }

    private companion object {
        const val COLUMNS = 2
        const val BUTTON_PADDING = 4
        const val BUTTON_WIDTH_FULL = 204
        const val BUTTON_WIDTH_HALF = 98
        const val WEBSITE_URL = "https://client.leicraftmc.de"

        val TITLE: Component = Component.translatable("leicraftclient.options.title")
        val CAPES: Component = Component.translatable("leicraftclient.options.capes")
        val COSMETICS: Component = Component.translatable("leicraftclient.options.cosmetics")
        val RELOAD: Component = Component.translatable("leicraftclient.options.reload")
        val RELOADING: Component = Component.translatable("leicraftclient.options.reloading")
        val WEBSITE: Component = Component.translatable("leicraftclient.options.website")
    }
}
