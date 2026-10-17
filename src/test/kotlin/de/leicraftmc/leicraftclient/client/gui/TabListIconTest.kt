package de.leicraftmc.leicraftclient.client.gui

import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals

class TabListIconTest {

    /** Each text run of the badge with the font it will actually be drawn in. */
    private fun runs(): List<Pair<String, FontDescription>> {
        val out = mutableListOf<Pair<String, FontDescription>>()
        TabListIcon.badge().visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                out += text to style.font
                Optional.empty()
            },
            Style.EMPTY,
        )
        return out
    }

    @Test
    fun `only the glyph uses the icon font`() {
        val runs = runs()
        assertEquals(listOf("", " "), runs.map { it.first })

        // A space drawn in the icon font has no glyph and shows vanilla's
        // missing-glyph box between the badge and the name.
        assertEquals(FontDescription.DEFAULT, runs[1].second, "the gap inherited the icon font")
    }
}
