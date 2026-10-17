package de.leicraftmc.leicraftclient.client.gui

/**
 * Pure layout maths for inserting a row into an already-arranged menu.
 *
 * Kept free of Minecraft types so it can be unit-tested on a plain JVM against
 * real widget coordinates.
 */
object MenuLayout {

    /** Vanilla's pause-menu row pitch: a 20px button plus 4px padding. */
    const val ROW_SPACING = 24

    /**
     * Moves every element whose top edge is at or below [rowY] down one row,
     * leaving the row at [rowY] free.
     *
     * [rowY] is taken as a value rather than read from the anchor widget: the
     * anchor is itself one of the moved elements, and reading its position
     * mid-loop would stop shifting the rest of its row as soon as it had moved.
     */
    fun <T> openRow(elements: Iterable<T>, rowY: Int, getY: (T) -> Int, setY: (T, Int) -> Unit) {
        for (element in elements) {
            val y = getY(element)
            if (y >= rowY) {
                setY(element, y + ROW_SPACING)
            }
        }
    }
}
