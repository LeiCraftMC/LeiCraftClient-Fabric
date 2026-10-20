package de.leicraftmc.leicraftclient.client.gui

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents

/**
 * Adds the LeiCraftClient entry to the pause menu.
 *
 * Done through Fabric's screen events rather than a mixin. By the time the
 * event fires, vanilla has already arranged its grid, so the entry is placed by
 * taking over the anchor's row and moving that row and everything below it down
 * by one.
 *
 * Mod Menu adds its "Mods" button into the same grid from a mixin inside
 * `PauseScreen.createPauseMenu`, so that button already exists here no matter
 * which mod loads first, and anchoring on it lands this entry directly above it.
 */
object PauseMenuEntry {

    private const val BUTTON_WIDTH = 204
    private const val BUTTON_HEIGHT = 20

    /** Mod Menu's "Mods" button; its label is `ModMenuScreenTexts.TITLE`. */
    private const val MOD_MENU_KEY = "modmenu.title"

    /** Vanilla's "Options..." button, the fallback when Mod Menu is absent. */
    private const val OPTIONS_KEY = "menu.options"

    private val LABEL: Component = Component.translatable("leicraftclient.menu.options")

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is PauseScreen) {
                insert(screen)
            }
        }
    }

    private fun insert(screen: PauseScreen) {
        val widgets = Screens.getWidgets(screen)

        // The pause menu only has the full grid when the game is actually
        // paused; the F3+Esc variant has no Options button and is left alone.
        val anchor = findAnchor(widgets) ?: return
        val rowX = anchor.x
        val rowY = anchor.y

        MenuLayout.openRow(widgets, rowY, { it.y }, { widget, y -> widget.y = y })

        val entry = Button.builder(LABEL) { _ ->
            Minecraft.getInstance().gui.setScreen(LeiCraftOptionsScreen(screen))
        }
            .bounds(rowX, rowY, BUTTON_WIDTH, BUTTON_HEIGHT)
            .build()

        // Fabric's widget list registers the button as renderable, selectable
        // and narratable alike. Inserting before the anchor rather than appending
        // keeps Tab navigation in top-to-bottom order.
        widgets.add(widgets.indexOf(anchor), entry)
    }

    /** Prefers Mod Menu's "Mods" button, falling back to vanilla's "Options...". */
    private fun findAnchor(widgets: List<AbstractWidget>): AbstractWidget? =
        widgets.firstOrNull { it is Button && it.translationKey() == MOD_MENU_KEY }
            ?: widgets.firstOrNull { it is Button && it.translationKey() == OPTIONS_KEY }

    private fun AbstractWidget.translationKey(): String? =
        (message.contents as? TranslatableContents)?.key
}
