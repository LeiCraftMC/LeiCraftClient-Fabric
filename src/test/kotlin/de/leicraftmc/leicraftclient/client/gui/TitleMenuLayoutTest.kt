package de.leicraftmc.leicraftclient.client.gui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays the title screen through [MenuLayout.openCentredRow].
 *
 * Coordinates follow vanilla's `TitleScreen.init` for a 480x270 screen (1080p
 * at GUI scale 4): the first row at `height / 4 + 48`, rows 24 apart, the
 * copyright line left out as [MenuEntry] leaves it out. Mod Menu's "classic"
 * button is modelled after its `ModMenuEventHandler.afterTitleScreenInit`,
 * which runs from the same screen event as [MenuEntry], in either order.
 */
class TitleMenuLayoutTest {

    private class Widget(val label: String, val x: Int, var y: Int)

    private fun vanillaMenu() = mutableListOf(
        Widget("Singleplayer", 140, 115),
        Widget("Multiplayer", 140, 139),
        Widget("Realms", 140, 163),
        Widget("Friends", 206, 187),
        Widget("Language", 230, 187),
        Widget("Accessibility", 254, 187),
        Widget("Options...", 140, 211),
        Widget("Quit Game", 242, 211),
    )

    /**
     * Mod Menu's classic style: every widget up to and including Realms (in
     * list order) moves up half a row, every later one down half a row, and
     * "Mods" goes in a row below Realms.
     */
    private fun addModMenu(widgets: MutableList<Widget>) {
        val realms = widgets.indexOfFirst { it.label == "Realms" }
        widgets.forEachIndexed { i, w -> w.y += if (i <= realms) -12 else 12 }
        widgets.add(realms + 1, Widget("Mods", 140, widgets[realms].y + 24))
    }

    /** Mirrors [MenuEntry]: below the lowest of Realms / Mods. */
    private fun addEntry(widgets: MutableList<Widget>) {
        val anchor = widgets.filter { it.label == "Realms" || it.label == "Mods" }.maxBy { it.y }
        val rowY = MenuLayout.openCentredRow(widgets, anchor.y + MenuLayout.ROW_SPACING, { it.y }, { w, y -> w.y = y })
        val index = widgets.indexOfFirst { it.y > rowY }
        widgets.add(if (index >= 0) index else widgets.size, Widget("LeiCraftClient", 140, rowY))
    }

    private fun List<Widget>.y(label: String) = single { it.label == label }.y

    @Test
    fun `the entry goes directly below realms and the menu stays centred`() {
        val widgets = vanillaMenu()
        addEntry(widgets)

        assertEquals(103, widgets.y("Singleplayer"))
        assertEquals(151, widgets.y("Realms"))
        assertEquals(175, widgets.y("LeiCraftClient"))
        assertEquals(199, widgets.y("Friends"))
        assertEquals(223, widgets.y("Options..."))
        assertEquals(223, widgets.y("Quit Game"))
    }

    @Test
    fun `mod menu first or last gives the same layout`() {
        val modMenuFirst = vanillaMenu().also { addModMenu(it) }.also { addEntry(it) }
        val entryFirst = vanillaMenu().also { addEntry(it) }.also { addModMenu(it) }

        assertEquals(modMenuFirst.map { it.label to it.y }, entryFirst.map { it.label to it.y })
        assertEquals(
            listOf("Realms", "Mods", "LeiCraftClient", "Friends"),
            entryFirst.map { it.label }.subList(2, 6),
            "Tab order should follow the rows",
        )
        assertEquals(139, entryFirst.y("Realms"))
        assertEquals(163, entryFirst.y("Mods"))
        assertEquals(187, entryFirst.y("LeiCraftClient"))
        assertEquals(211, entryFirst.y("Friends"))
    }

    @Test
    fun `rows stay 24 apart and clear of the copyright line`() {
        val widgets = vanillaMenu().also { addModMenu(it) }.also { addEntry(it) }
        val rows = widgets.map { it.y }.distinct().sorted()

        assertTrue(rows.zipWithNext().all { (a, b) -> b - a == MenuLayout.ROW_SPACING }, "rows: $rows")
        // The copyright line is drawn at height - 10.
        assertTrue(rows.last() + 20 <= 270 - 10, "bottom row at ${rows.last()} runs into the copyright line")
    }
}
