package de.leicraftmc.leicraftclient.client.gui

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.PlainTextButton
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.contents.TranslatableContents

/**
 * Adds the LeiCraftClient entry to the title screen and the pause menu.
 *
 * Done through Fabric's screen events rather than a mixin. By the time the
 * event fires, vanilla has already laid out its buttons, so the entry is placed
 * by opening a row ([MenuLayout]) and putting it there.
 */
object MenuEntry {

    private const val PAUSE_BUTTON_WIDTH = 204
    private const val TITLE_BUTTON_WIDTH = 200
    private const val BUTTON_HEIGHT = 20

    /** Mod Menu's "Mods" button; its label is `ModMenuScreenTexts.TITLE`. */
    private const val MOD_MENU_KEY = "modmenu.title"

    /** Vanilla's "Options..." button, the pause-menu fallback when Mod Menu is absent. */
    private const val OPTIONS_KEY = "menu.options"

    /**
     * Title-screen buttons the entry goes below: Realms, Mod Menu's "Mods"
     * (Mod Menu can also replace Realms with it) and, in the demo, "Reset Demo
     * World".
     */
    private val TITLE_ANCHOR_KEYS = setOf("menu.online", MOD_MENU_KEY, "menu.resetdemo")

    private val LABEL: Component = Component.translatable("leicraftclient.menu.options")

    fun register() {
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            when (screen) {
                is TitleScreen -> insertIntoTitle(screen)
                is PauseScreen -> insertIntoPause(screen)
            }
        }
    }

    /**
     * The entry takes the anchor's row and that row and everything below it
     * move down by one.
     *
     * Mod Menu adds its "Mods" button into the same grid from a mixin inside
     * `PauseScreen.createPauseMenu`, so that button already exists here no
     * matter which mod loads first, and anchoring on it lands this entry
     * directly above it.
     */
    private fun insertIntoPause(screen: PauseScreen) {
        val widgets = Screens.getWidgets(screen)

        // The pause menu only has the full grid when the game is actually
        // paused; the F3+Esc variant has no Options button and is left alone.
        val anchor = findPauseAnchor(widgets) ?: return
        val rowX = anchor.x
        val rowY = anchor.y

        MenuLayout.openRow(widgets, rowY, { it.y }, { widget, y -> widget.y = y })

        val entry = entryButton(screen).bounds(rowX, rowY, PAUSE_BUTTON_WIDTH, BUTTON_HEIGHT).build()

        // Fabric's widget list registers the button as renderable, selectable
        // and narratable alike. Inserting before the anchor rather than appending
        // keeps Tab navigation in top-to-bottom order.
        widgets.add(widgets.indexOf(anchor), entry)
    }

    /**
     * The entry goes into a new row directly below Realms, or below Mod Menu's
     * "Mods" when that is the lower of the two.
     *
     * Mod Menu adds "Mods" from its own screen event, so it may run before or
     * after this. Both open a centred row ([MenuLayout.openCentredRow]) and Mod
     * Menu shifts by list position, so the result is Realms, Mods, LeiCraftClient
     * either way.
     */
    private fun insertIntoTitle(screen: TitleScreen) {
        val widgets = Screens.getWidgets(screen)
        val anchor = findTitleAnchor(screen, widgets) ?: return

        // The copyright line is pinned to the bottom edge, not part of the menu.
        val menu = widgets.filter { it !is PlainTextButton }
        val rowY = MenuLayout.openCentredRow(menu, anchor.y + MenuLayout.ROW_SPACING, { it.y }, { widget, y -> widget.y = y })

        val entry = entryButton(screen)
            .bounds(screen.width / 2 - TITLE_BUTTON_WIDTH / 2, rowY, TITLE_BUTTON_WIDTH, BUTTON_HEIGHT)
            .build()

        // Before the first button below the new row, so Tab keeps top-to-bottom order.
        val index = widgets.indexOfFirst { it.y > rowY && it !is PlainTextButton }
        if (index >= 0) widgets.add(index, entry) else widgets.add(entry)
    }

    private fun entryButton(screen: Screen): Button.Builder =
        Button.builder(LABEL) { _ -> Minecraft.getInstance().gui.setScreen(LeiCraftOptionsScreen(screen)) }

    /** Prefers Mod Menu's "Mods" button, falling back to vanilla's "Options...". */
    private fun findPauseAnchor(widgets: List<AbstractWidget>): AbstractWidget? =
        widgets.firstOrNull { it is Button && it.translationKey() == MOD_MENU_KEY }
            ?: widgets.firstOrNull { it is Button && it.translationKey() == OPTIONS_KEY }

    /**
     * The lowest anchor button in the centre column. Mod Menu's icon-only style
     * puts "Mods" beside the column, so that one does not count.
     */
    private fun findTitleAnchor(screen: Screen, widgets: List<AbstractWidget>): AbstractWidget? {
        val left = screen.width / 2 - TITLE_BUTTON_WIDTH / 2
        val right = left + TITLE_BUTTON_WIDTH
        return widgets
            .filter { it is Button && it.translationKey() in TITLE_ANCHOR_KEYS && it.x < right && it.right > left }
            .maxByOrNull { it.y }
    }

    private fun AbstractWidget.translationKey(): String? =
        (message.contents as? TranslatableContents)?.key
}
