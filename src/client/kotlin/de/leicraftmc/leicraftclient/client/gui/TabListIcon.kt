package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier

/**
 * The little LeiCraft mark shown beside a player's name in the tab list.
 *
 * It is a glyph in a bitmap font the mod ships
 * (`assets/leicraftclient/font/icons.json`) rather than a blit, so it can be
 * appended to any [Component] and is positioned and scaled by the font
 * renderer. Swap `textures/gui/tab_icon.png` to change the artwork.
 */
object TabListIcon {

    /** Private-use code point, so it can never collide with real text. */
    private const val GLYPH = ""

    private val FONT: Identifier = Identifier.fromNamespaceAndPath(LeiCraftClient.MOD_ID, "icons")

    /**
     * The glyph and the gap after it are siblings under an unstyled root. A
     * component's children inherit its style, so a space appended *to* the glyph
     * would be drawn in the icon font -- which has no space character and so
     * renders the missing-glyph box instead.
     */
    private val BADGE: Component = Component.empty()
        .append(Component.literal(GLYPH).setStyle(Style.EMPTY.withFont(FontDescription.Resource(FONT))))
        .append(" ")

    fun badge(): Component = BADGE
}
