package de.leicraftmc.leicraftclient.client.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays real pause-menu coordinates through [MenuLayout.openRow].
 *
 * The first case is the widget dump logged by the dev client (GUI scale 2,
 * 960-wide screen). It is the layout that exposed a bug where World Options
 * stayed put underneath the new entry: the shift compared against the anchor's
 * live position, which moved as soon as the anchor itself had been shifted.
 */
class MenuLayoutTest {

    private class Widget(val label: String, val x: Int, var y: Int, val width: Int)

    /** Exactly as logged by the dev client, with no Mod Menu installed. */
    private fun vanillaMenu() = listOf(
        Widget("Back to Game", 378, 134, 204),
        Widget("Advancements", 378, 158, 98),
        Widget("Statistics", 484, 158, 98),
        Widget("Report Bugs", 434, 182, 20),
        Widget("Give Feedback", 458, 182, 20),
        Widget("Friends", 482, 182, 20),
        Widget("Player Reporting", 506, 182, 20),
        Widget("Options...", 378, 206, 98),
        Widget("World Options...", 484, 206, 98),
        Widget("Disconnect", 378, 230, 204),
        Widget("Game Menu", 454, 40, 52),
    )

    /** The same menu with Mod Menu's full-width "Mods" row in the grid. */
    private fun modMenuMenu() = listOf(
        Widget("Back to Game", 378, 134, 204),
        Widget("Advancements", 378, 158, 98),
        Widget("Statistics", 484, 158, 98),
        Widget("Report Bugs", 434, 182, 20),
        Widget("Mods", 378, 206, 204),
        Widget("Options...", 378, 230, 98),
        Widget("World Options...", 484, 230, 98),
        Widget("Disconnect", 378, 254, 204),
        Widget("Game Menu", 454, 40, 52),
    )

    private fun open(widgets: List<Widget>, anchorLabel: String): Int {
        // Mirrors PauseMenuEntry: the anchor's y is read once, up front.
        val rowY = widgets.single { it.label == anchorLabel }.y
        MenuLayout.openRow(widgets, rowY, { it.y }, { w, y -> w.y = y })
        return rowY
    }

    private fun List<Widget>.y(label: String) = single { it.label == label }.y

    @Test
    fun `world options moves with options when anchored on options`() {
        val widgets = vanillaMenu()
        open(widgets, "Options...")

        assertEquals(230, widgets.y("Options..."))
        assertEquals(230, widgets.y("World Options..."), "World Options was left under the new entry")
        assertEquals(254, widgets.y("Disconnect"))
    }

    @Test
    fun `rows above the anchor stay where they are`() {
        val widgets = vanillaMenu()
        open(widgets, "Options...")

        assertEquals(134, widgets.y("Back to Game"))
        assertEquals(158, widgets.y("Advancements"))
        assertEquals(182, widgets.y("Report Bugs"))
        assertEquals(40, widgets.y("Game Menu"))
    }

    @Test
    fun `the freed row overlaps nothing`() {
        for ((menu, anchor) in listOf(vanillaMenu() to "Options...", modMenuMenu() to "Mods")) {
            val rowY = open(menu, anchor)
            val rowBottom = rowY + 20
            val clashes = menu.filter { it.y < rowBottom && it.y + 20 > rowY }
            assertTrue(clashes.isEmpty(), "anchor $anchor: still in the new row: ${clashes.map { it.label }}")
        }
    }

    @Test
    fun `with mod menu the entry takes the mods row and mods moves below it`() {
        val widgets = modMenuMenu()
        val rowY = open(widgets, "Mods")

        assertEquals(206, rowY, "the entry should sit where Mods was, i.e. directly above it")
        assertEquals(230, widgets.y("Mods"))
        assertEquals(254, widgets.y("Options..."))
        assertEquals(254, widgets.y("World Options..."))
        assertEquals(278, widgets.y("Disconnect"))
    }
}
