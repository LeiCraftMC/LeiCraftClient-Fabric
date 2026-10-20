package de.leicraftmc.leicraftclient.client.gui

import de.leicraftmc.leicraftclient.client.LeiCraftClient
import de.leicraftmc.leicraftclient.client.api.ApiModels
import de.leicraftmc.leicraftclient.client.api.ApiResult
import de.leicraftmc.leicraftclient.client.api.BackendGate
import de.leicraftmc.leicraftclient.client.api.LeiCraftApi
import de.leicraftmc.leicraftclient.client.profiles.OwnPlayer
import de.leicraftmc.leicraftclient.client.profiles.PlayerProfiles
import kotlinx.coroutines.launch
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ObjectSelectionList
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.Pose
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.atan

/**
 * The in-game wardrobe: pick a cape and up to ten cosmetics from what the
 * linked LeiCraft account can equip, see them on a live preview of the own
 * player, and save them as the loadout.
 *
 * Data comes from `GET /v1/client/wardrobe` (paged, searchable),
 * `GET /v1/client/loadout` and is saved with `PUT /v1/client/loadout`. While the
 * screen is open, the unsaved selection is laid over the own player through
 * [PlayerProfiles.setPreview], so the preview -- and the player in the world
 * behind the screen -- show it before saving. Closing in any way drops it.
 *
 * Built only from vanilla widgets; positions are computed from the scaled
 * screen size in [init], which vanilla calls again on every resize.
 */
class WardrobeScreen(private val parent: Screen?) : Screen(TITLE) {

    private enum class Tab(val type: String, val label: Component) {
        CAPES(ApiModels.Product.TYPE_CAPE, Component.translatable("leicraftclient.wardrobe.tab.capes")),
        COSMETICS(ApiModels.Product.TYPE_COSMETIC, Component.translatable("leicraftclient.wardrobe.tab.cosmetics")),
    }

    // -- state; survives init() being re-run on resize ------------------------

    private var tab = Tab.CAPES
    private var searchText = ""
    private var searchDueAt = 0L
    private var selection: WardrobeSelection? = null
    private val items = mutableListOf<ApiModels.WardrobeItem>()
    private var hasMore = false
    private var loadingItems = false
    private var requestGeneration = 0
    private var saving = false
    private var started = false
    private var status: Component? = null
    private var previewYaw = 0f
    private var draggingPreview = false

    // -- widgets and layout ---------------------------------------------------

    private var list: ItemList? = null
    private var capesTab: Button? = null
    private var cosmeticsTab: Button? = null
    private var loadMoreButton: Button? = null
    private var variantButton: Button? = null
    private var saveButton: Button? = null

    private var previewX0 = 0
    private var previewY0 = 0
    private var previewX1 = 0
    private var previewY1 = 0

    override fun init() {
        val top = TOP
        val contentBottom = height - FOOTER_HEIGHT
        val previewWidth = (width * 0.3f).toInt().coerceIn(MIN_PREVIEW_WIDTH, MAX_PREVIEW_WIDTH)
        previewX0 = MARGIN
        previewY0 = top
        previewX1 = MARGIN + previewWidth
        previewY1 = contentBottom

        val rightX = previewX1 + MARGIN
        val rightWidth = (width - rightX - MARGIN).coerceAtLeast(MIN_LIST_WIDTH)
        val halfWidth = (rightWidth - GAP) / 2

        capesTab = addRenderableWidget(
            Button.builder(Tab.CAPES.label) { switchTab(Tab.CAPES) }.bounds(rightX, top, halfWidth, BUTTON_HEIGHT).build(),
        )
        cosmeticsTab = addRenderableWidget(
            Button.builder(Tab.COSMETICS.label) { switchTab(Tab.COSMETICS) }
                .bounds(rightX + halfWidth + GAP, top, rightWidth - halfWidth - GAP, BUTTON_HEIGHT)
                .build(),
        )

        val search = EditBox(font, rightX, top + BUTTON_HEIGHT + GAP, rightWidth, SEARCH_HEIGHT, SEARCH_HINT)
        search.setHint(SEARCH_HINT)
        search.setMaxLength(LeiCraftApi.MAX_SEARCH_LENGTH)
        search.value = searchText
        search.setResponder { text ->
            if (text != searchText) {
                searchText = text
                searchDueAt = System.currentTimeMillis() + SEARCH_DEBOUNCE_MS
            }
        }
        addRenderableWidget(search)

        val listY = top + BUTTON_HEIGHT + GAP + SEARCH_HEIGHT + GAP + 2
        val rowY = contentBottom - BUTTON_HEIGHT
        val listHeight = (rowY - GAP - 2 - listY).coerceAtLeast(ROW_HEIGHT)
        list = addRenderableWidget(ItemList(minecraft, rightWidth, listHeight, rightX, listY)).also { fillList(it, resetScroll = false) }

        loadMoreButton = addRenderableWidget(
            Button.builder(LOAD_MORE) { loadMore() }.bounds(rightX, rowY, halfWidth, BUTTON_HEIGHT).build(),
        )
        variantButton = addRenderableWidget(
            Button.builder(variantLabel(null)) { cycleVariant() }
                .bounds(rightX + halfWidth + GAP, rowY, rightWidth - halfWidth - GAP, BUTTON_HEIGHT)
                .build(),
        )

        val footerY = height - BUTTON_HEIGHT - MARGIN
        saveButton = addRenderableWidget(
            Button.builder(SAVE) { save() }.bounds(width / 2 - FOOTER_BUTTON_WIDTH - GAP / 2, footerY, FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build(),
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.bounds(width / 2 + GAP / 2, footerY, FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build(),
        )

        updateButtons()

        if (!started) {
            started = true
            loadLoadout()
            reloadItems()
        }
    }

    // -- data -----------------------------------------------------------------

    private fun loadLoadout() {
        status = LOADING
        LeiCraftClient.scope.launch {
            val result = LeiCraftApi.loadout()
            screenExecutor.execute {
                when (result) {
                    is ApiResult.Ok -> {
                        selection = WardrobeSelection(result.data)
                        if (status == LOADING) status = null
                        updatePreview()
                        list?.let { fillList(it, resetScroll = false) }
                    }
                    is ApiResult.Error -> status = errorText(result)
                }
                updateButtons()
            }
        }
    }

    private fun reloadItems() {
        items.clear()
        hasMore = false
        list?.let { fillList(it, resetScroll = true) }
        fetchPage(offset = 0)
    }

    private fun loadMore() {
        if (!loadingItems && hasMore) fetchPage(offset = items.size)
    }

    private fun fetchPage(offset: Int) {
        val generation = ++requestGeneration
        val type = tab.type
        val search = searchText
        loadingItems = true
        updateButtons()

        LeiCraftClient.scope.launch {
            val result = LeiCraftApi.wardrobe(type, search, offset)
            screenExecutor.execute {
                // A newer search or tab switch superseded this page.
                if (generation != requestGeneration) return@execute
                loadingItems = false
                when (result) {
                    is ApiResult.Ok -> {
                        if (offset == 0) items.clear()
                        items += result.data
                        hasMore = result.data.size >= LeiCraftApi.WARDROBE_PAGE_SIZE
                        list?.let { fillList(it, resetScroll = offset == 0) }
                    }
                    is ApiResult.Error -> status = errorText(result)
                }
                updateButtons()
            }
        }
    }

    private fun save() {
        val current = selection ?: return
        saving = true
        status = SAVING
        updateButtons()

        LeiCraftClient.scope.launch {
            val result = LeiCraftApi.saveLoadout(current.toUpdate())
            screenExecutor.execute {
                saving = false
                when (result) {
                    is ApiResult.Ok -> {
                        PlayerProfiles.clearPreview()
                        // Show the saved loadout right away; the refresh it also
                        // queues confirms it from the lookup a moment later.
                        OwnPlayer.applyLoadout(result.data)
                        Notices.toast(SAVED, null)
                        minecraft.gui.setScreen(parent)
                    }
                    is ApiResult.Error -> {
                        status = errorText(result)
                        updateButtons()
                    }
                }
            }
        }
    }

    // -- interaction ----------------------------------------------------------

    private fun switchTab(next: Tab) {
        if (tab == next) return
        tab = next
        // Keep "Loading…" or a loadout error; anything else was about the old tab.
        if (selection != null) status = null
        reloadItems()
    }

    private fun onEntryClicked(entry: ItemEntry) {
        val current = selection ?: return
        val product = entry.item?.product
        when (tab) {
            Tab.CAPES -> current.selectCape(product)
            Tab.COSMETICS -> if (product != null) {
                status = if (current.toggle(product) == WardrobeSelection.Toggle.LIMIT_REACHED) {
                    Component.translatable("leicraftclient.wardrobe.limit", WardrobeSelection.MAX_COSMETICS).withStyle(ChatFormatting.RED)
                } else {
                    null
                }
            }
        }
        updatePreview()
        updateButtons()
    }

    private fun cycleVariant() {
        val current = selection ?: return
        val product = selectedCosmetic() ?: return
        current.cycleVariant(product.id)
        updatePreview()
        updateButtons()
    }

    /** The cosmetic the variant button acts on: the selected row, if it is equipped. */
    private fun selectedCosmetic(): ApiModels.Product? {
        if (tab != Tab.COSMETICS) return null
        val product = list?.selected?.item?.product ?: return null
        return product.takeIf { selection?.isEquipped(it.id) == true }
    }

    private fun updatePreview() {
        val current = selection ?: return
        val name = OwnPlayer.name()
        val uuids = OwnPlayer.uuids()
        PlayerProfiles.setPreview(uuids, OwnPlayer.profileOf(uuids.first(), name, current.toLoadout()))
    }

    private fun updateButtons() {
        capesTab?.active = tab != Tab.CAPES
        cosmeticsTab?.active = tab != Tab.COSMETICS
        loadMoreButton?.active = hasMore && !loadingItems
        loadMoreButton?.message = if (loadingItems) LOADING_SHORT else LOAD_MORE

        val cosmetic = selectedCosmetic()
        variantButton?.active = cosmetic != null && cosmetic.variants.size > 1 && !saving
        variantButton?.message = variantLabel(cosmetic?.let { selection?.variantOf(it.id) })

        saveButton?.active = selection?.isDirty == true && !saving
    }

    private fun fillList(target: ItemList, resetScroll: Boolean) {
        val previous = target.selected?.item?.product?.id
        val entries = buildList {
            if (tab == Tab.CAPES) add(ItemEntry(null))
            items.forEach { add(ItemEntry(it)) }
        }
        target.setItems(entries)
        if (resetScroll) target.setScrollAmount(0.0)

        val current = selection
        val toSelect = when {
            tab == Tab.CAPES && current != null -> entries.firstOrNull { it.item?.product?.id == current.cape?.id }
            previous != null -> entries.firstOrNull { it.item?.product?.id == previous }
            else -> null
        }
        target.selected = toSelect
    }

    override fun tick() {
        super.tick()
        if (searchDueAt != 0L && System.currentTimeMillis() >= searchDueAt) {
            searchDueAt = 0L
            reloadItems()
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.x() >= previewX0 && event.x() < previewX1 && event.y() >= previewY0 && event.y() < previewY1) {
            draggingPreview = true
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (draggingPreview) {
            previewYaw = (previewYaw + dx.toFloat() * DRAG_DEGREES_PER_PIXEL) % 360f
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (draggingPreview) {
            draggingPreview = false
            return true
        }
        return super.mouseReleased(event)
    }

    // -- drawing --------------------------------------------------------------

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.fill(previewX0, previewY0, previewX1, previewY1, PANEL_COLOR)

        val player = minecraft.player
        if (player == null) {
            graphics.centeredText(font, NO_PLAYER, (previewX0 + previewX1) / 2, (previewY0 + previewY1) / 2, MUTED_COLOR)
            return
        }
        val size = minOf((previewY1 - previewY0) * 0.36f, (previewX1 - previewX0) * 0.6f).toInt().coerceAtLeast(10)
        extractPlayer(graphics, size, mouseX.toFloat(), mouseY.toFloat(), player)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, a)
        graphics.centeredText(font, title, width / 2, TITLE_Y, TEXT_COLOR)
        graphics.centeredText(font, DRAG_HINT, (previewX0 + previewX1) / 2, previewY1 - font.lineHeight - 4, MUTED_COLOR)

        val current = selection
        val line = status ?: current?.let {
            Component.translatable("leicraftclient.wardrobe.count", it.equippedCosmetics.size, WardrobeSelection.MAX_COSMETICS)
                .withStyle(ChatFormatting.GRAY)
        }
        if (line != null) {
            graphics.centeredText(font, line, width / 2, height - FOOTER_HEIGHT + 6, TEXT_COLOR)
        }
    }

    /**
     * Vanilla's `InventoryScreen.extractEntityInInventoryFollowsMouse`, with an
     * extra body yaw from dragging so the cape on the back can be seen too.
     */
    private fun extractPlayer(graphics: GuiGraphicsExtractor, size: Int, mouseX: Float, mouseY: Float, entity: LivingEntity) {
        val centerX = (previewX0 + previewX1) / 2f
        val centerY = (previewY0 + previewY1) / 2f
        val xAngle = atan((centerX - mouseX) / 40f)
        val yAngle = atan((centerY - mouseY) / 40f)
        val rotation = Quaternionf().rotateZ(Math.PI.toFloat())
        val xRotation = Quaternionf().rotateX(yAngle * 20f * DEGREES_TO_RADIANS)
        rotation.mul(xRotation)

        val renderer = Minecraft.getInstance().entityRenderDispatcher.getRenderer(entity)
        val state = renderer.createRenderState(entity, 1.0f)
        state.shadowPieces.clear()
        state.outlineColor = 0
        if (state is LivingEntityRenderState) {
            state.bodyRot = 180f + xAngle * 20f + previewYaw
            state.yRot = xAngle * 20f
            state.xRot = if (state.pose != Pose.FALL_FLYING) -yAngle * 20f else 0f
            state.boundingBoxWidth = state.boundingBoxWidth / state.scale
            state.boundingBoxHeight = state.boundingBoxHeight / state.scale
            state.scale = 1.0f
        }

        val translation = Vector3f(0f, state.boundingBoxHeight / 2f + 0.0625f, 0f)
        graphics.entity(state, size.toFloat(), translation, rotation, xRotation, previewX0, previewY0, previewX1, previewY1)
    }

    // -- closing --------------------------------------------------------------

    override fun onClose() {
        PlayerProfiles.clearPreview()
        minecraft.gui.setScreen(parent)
    }

    override fun removed() {
        // However the screen goes away, the unsaved preview must not stick.
        PlayerProfiles.clearPreview()
        super.removed()
    }

    private fun errorText(error: ApiResult.Error): Component {
        val text = when {
            error.isRateLimited -> Component.translatable(
                "leicraftclient.error.rate_limited",
                error.retryAfterSeconds ?: BackendGate.DEFAULT_BACKOFF_SECONDS,
            )
            error.isNetworkError -> Component.translatable("leicraftclient.error.network", error.message)
            else -> Component.literal(error.message)
        }
        return text.withStyle(ChatFormatting.RED)
    }

    private fun variantLabel(variant: ApiModels.Variant?): Component =
        Component.translatable(
            "leicraftclient.wardrobe.variant",
            variant?.let { Component.literal(it.name) } ?: Component.translatable("leicraftclient.wardrobe.variant.auto"),
        )

    // -- list -----------------------------------------------------------------

    private inner class ItemList(minecraft: Minecraft, width: Int, height: Int, x: Int, y: Int) :
        ObjectSelectionList<ItemEntry>(minecraft, width, height, y, ROW_HEIGHT) {

        init {
            updateSizeAndPosition(width, height, x, y)
        }

        fun setItems(entries: List<ItemEntry>) {
            replaceEntries(entries)
            refreshScrollAmount()
        }

        override fun setSelected(selected: ItemEntry?) {
            super.setSelected(selected)
            // Keyboard navigation moves the selection too; the variant button follows it.
            updateButtons()
        }

        override fun getRowWidth(): Int = width - 2 * (scrollbarWidth() + 2)

        override fun scrollBarX(): Int = right - scrollbarWidth()
    }

    /** One row; [item] null is the "No cape" row. */
    private inner class ItemEntry(val item: ApiModels.WardrobeItem?) : ObjectSelectionList.Entry<ItemEntry>() {

        override fun extractContent(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
            val x = contentX + 2
            val y = contentY + 1
            val right = contentRight - 2
            val current = selection
            val product = item?.product

            val equipped = when {
                current == null -> false
                product == null -> current.cape == null
                product.isCape -> current.isCapeSelected(product.id)
                else -> current.isEquipped(product.id)
            }

            val marker: Component? = when {
                equipped -> EQUIPPED
                item?.owned == true -> OWNED
                else -> null
            }
            val markerWidth = marker?.let { font.width(it) + 6 } ?: 0
            val textWidth = (right - x - markerWidth).coerceAtLeast(10)

            val name = product?.name ?: NO_CAPE.string
            graphics.text(font, font.plainSubstrByWidth(name, textWidth), x, y, if (equipped) EQUIPPED_COLOR else TEXT_COLOR)

            val secondLine = when {
                item == null -> ""
                product?.isCape == false && equipped -> {
                    val pinned = current?.variantOf(product.id)
                    "${item.page.name} · ${pinned?.name ?: Component.translatable("leicraftclient.wardrobe.variant.auto").string}"
                }
                else -> item.page.name
            }
            if (secondLine.isNotEmpty()) {
                graphics.text(font, font.plainSubstrByWidth(secondLine, textWidth), x, y + font.lineHeight + 2, MUTED_COLOR)
            }

            if (marker != null) {
                graphics.text(font, marker, right - font.width(marker), y, TEXT_COLOR)
            }
        }

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            list?.selected = this
            onEntryClicked(this)
            return true
        }

        override fun keyPressed(event: KeyEvent): Boolean {
            if (event.isSelection) {
                onEntryClicked(this)
                return true
            }
            return super.keyPressed(event)
        }

        override fun getNarration(): Component =
            Component.translatable("narrator.select", item?.product?.name ?: NO_CAPE.string)
    }

    private companion object {
        const val MARGIN = 8
        const val GAP = 4
        const val TOP = 24
        const val TITLE_Y = 9
        const val FOOTER_HEIGHT = 44
        const val BUTTON_HEIGHT = 20
        const val SEARCH_HEIGHT = 16
        const val ROW_HEIGHT = 24
        const val FOOTER_BUTTON_WIDTH = 100
        const val MIN_PREVIEW_WIDTH = 90
        const val MAX_PREVIEW_WIDTH = 180
        const val MIN_LIST_WIDTH = 120
        const val SEARCH_DEBOUNCE_MS = 400L
        const val DRAG_DEGREES_PER_PIXEL = 1.5f
        const val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()

        const val TEXT_COLOR = -1
        const val MUTED_COLOR = 0xFFA0A0A0.toInt()
        const val EQUIPPED_COLOR = 0xFF55FF55.toInt()
        const val PANEL_COLOR = 0x66000000

        val TITLE: Component = Component.translatable("leicraftclient.wardrobe.title")
        val SEARCH_HINT: Component = Component.translatable("leicraftclient.wardrobe.search").withStyle(EditBox.SEARCH_HINT_STYLE)
        val LOAD_MORE: Component = Component.translatable("leicraftclient.wardrobe.load_more")
        val LOADING_SHORT: Component = Component.translatable("leicraftclient.wardrobe.loading_short")
        val SAVE: Component = Component.translatable("leicraftclient.wardrobe.save")
        val SAVED: Component = Component.translatable("leicraftclient.wardrobe.saved")
        val NO_CAPE: Component = Component.translatable("leicraftclient.wardrobe.no_cape")
        val NO_PLAYER: Component = Component.translatable("leicraftclient.wardrobe.no_player")
        val DRAG_HINT: Component = Component.translatable("leicraftclient.wardrobe.drag_hint")
        val EQUIPPED: Component = Component.translatable("leicraftclient.wardrobe.equipped").withStyle(ChatFormatting.GREEN)
        val OWNED: Component = Component.translatable("leicraftclient.wardrobe.owned").withStyle(ChatFormatting.GOLD)
        val LOADING: Component = Component.translatable("leicraftclient.wardrobe.loading").withStyle(ChatFormatting.GRAY)
        val SAVING: Component = Component.translatable("leicraftclient.wardrobe.saving").withStyle(ChatFormatting.GRAY)
    }
}
