package de.leicraftmc.leicraftclient.client.api

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import java.util.UUID

/**
 * The API's response shapes, read by hand from Gson's tree.
 *
 * Gson's reflective binding would bypass Kotlin's constructors and let a
 * missing field turn into a `null` in a non-null property, so each type reads
 * itself and fails loudly ([JsonParseException]) on a missing required field.
 * [ApiResult.parse] turns that into an error result.
 *
 * Field names follow the API (`docs/client-integration.md`, `/docs/v1/openapi`),
 * converted to camelCase.
 */
object ApiModels {

    /** A stored file; `id` is the SHA-256 of its bytes and `url` never changes content. */
    data class AssetRef(val id: String, val url: String) {
        companion object {
            fun read(o: JsonObject) = AssetRef(o.str("id"), o.str("url"))
        }
    }

    data class PublicUser(val id: Int, val displayName: String, val avatarUrl: String) {
        companion object {
            fun read(o: JsonObject) = PublicUser(o.int("id"), o.str("display_name"), o.str("avatar_url"))
        }
    }

    data class MinecraftAccount(
        val uuid: UUID,
        val name: String,
        /** `"wide"` or `"slim"`. */
        val skinModel: String,
        val usesClient: Boolean,
        val linkedAt: Long?,
    ) {
        companion object {
            fun read(o: JsonObject) = MinecraftAccount(
                uuid = o.uuid("uuid"),
                name = o.str("name"),
                skinModel = o.str("skin_model"),
                usesClient = o.bool("uses_client"),
                linkedAt = o.longOrNull("linked_at"),
            )
        }
    }

    /** Model and texture of a cosmetic for one skin model. */
    data class Variant(
        val id: Int,
        val name: String,
        /** `"any"`, `"wide"` or `"slim"`. */
        val skinModel: String,
        val model: AssetRef,
        val texture: AssetRef,
        val sortOrder: Int,
    ) {
        companion object {
            fun read(o: JsonObject) = Variant(
                id = o.int("id"),
                name = o.str("name"),
                skinModel = o.str("skin_model"),
                model = AssetRef.read(o.obj("model")),
                texture = AssetRef.read(o.obj("texture")),
                sortOrder = o.intOrNull("sort_order") ?: 0,
            )
        }
    }

    data class Product(
        val id: Int,
        val pageId: Int,
        /** `"cape"` or `"cosmetic"`. */
        val type: String,
        val name: String,
        val description: String,
        /** `"free"` or `"exclusive"`. */
        val availability: String,
        /** Cape texture (capes only). */
        val texture: AssetRef?,
        val preview: AssetRef?,
        /** Cosmetic variants (cosmetics only). */
        val variants: List<Variant>,
    ) {
        val isCape: Boolean get() = type == TYPE_CAPE

        companion object {
            const val TYPE_CAPE = "cape"
            const val TYPE_COSMETIC = "cosmetic"

            fun read(o: JsonObject) = Product(
                id = o.int("id"),
                pageId = o.int("page_id"),
                type = o.str("type"),
                name = o.str("name"),
                description = o.strOrNull("description").orEmpty(),
                availability = o.strOrNull("availability") ?: "free",
                texture = o.objOrNull("texture")?.let(AssetRef::read),
                preview = o.objOrNull("preview")?.let(AssetRef::read),
                variants = o.list("variants", Variant::read),
            )
        }
    }

    /** The page a wardrobe item belongs to. */
    data class PageRef(val id: Int, val category: String, val slug: String, val name: String) {
        companion object {
            fun read(o: JsonObject) = PageRef(o.int("id"), o.str("category"), o.str("slug"), o.str("name"))
        }
    }

    /** Something the linked user can equip right now. */
    data class WardrobeItem(val product: Product, val page: PageRef, val owned: Boolean) {
        companion object {
            fun read(o: JsonObject) = WardrobeItem(Product.read(o.obj("product")), PageRef.read(o.obj("page")), o.bool("owned"))

            fun readList(data: JsonElement?): List<WardrobeItem> = data.array().items(::read)
        }
    }

    data class LoadoutCosmetic(
        val product: Product,
        /** Pinned variant, or null to pick by the wearer's skin model. */
        val variantId: Int?,
    ) {
        companion object {
            fun read(o: JsonObject) = LoadoutCosmetic(Product.read(o.obj("product")), o.intOrNull("variant_id"))
        }
    }

    /** What the own Minecraft account has equipped. */
    data class Loadout(val cape: Product?, val cosmetics: List<LoadoutCosmetic>) {
        companion object {
            fun read(data: JsonElement?): Loadout {
                val o = data.objectOrThrow()
                return Loadout(o.objOrNull("cape")?.let(Product::read), o.list("cosmetics", LoadoutCosmetic::read))
            }
        }
    }

    /** Body of `PUT /v1/client/loadout`; the whole loadout is replaced. */
    data class LoadoutUpdate(val capeProductId: Int?, val cosmetics: List<Pair<Int, Int?>>) {
        fun toJson(): JsonObject = JsonObject().apply {
            // Required key even when empty: the API reads null as "no cape".
            if (capeProductId == null) add("cape_product_id", JsonNull.INSTANCE) else addProperty("cape_product_id", capeProductId)
            add("cosmetics", JsonArray().apply {
                for ((productId, variantId) in cosmetics) {
                    add(JsonObject().apply {
                        addProperty("product_id", productId)
                        if (variantId == null) add("variant_id", JsonNull.INSTANCE) else addProperty("variant_id", variantId)
                    })
                }
            })
        }
    }

    data class ProfileCape(val productId: Int, val texture: AssetRef) {
        companion object {
            fun read(o: JsonObject) = ProfileCape(o.int("product_id"), AssetRef.read(o.obj("texture")))
        }
    }

    data class ProfileCosmetic(val productId: Int, val variantId: Int?, val variants: List<Variant>) {
        companion object {
            fun read(o: JsonObject) = ProfileCosmetic(o.int("product_id"), o.intOrNull("variant_id"), o.list("variants", Variant::read))
        }
    }

    /** The public, render-ready profile of a player (`POST /v1/players/lookup`). */
    data class PlayerProfile(
        val uuid: UUID,
        val name: String,
        /** Used the LeiCraft Client within the last 30 days. */
        val usesClient: Boolean,
        val cape: ProfileCape?,
        val cosmetics: List<ProfileCosmetic>,
    ) {
        companion object {
            fun read(o: JsonObject) = PlayerProfile(
                uuid = o.uuid("uuid"),
                name = o.str("name"),
                usesClient = o.bool("uses_client"),
                cape = o.objOrNull("cape")?.let(ProfileCape::read),
                cosmetics = o.list("cosmetics", ProfileCosmetic::read),
            )

            /** A lookup answer. A malformed entry is dropped, not the whole batch. */
            fun readList(data: JsonElement?): List<PlayerProfile> = data.array().items(::read)
        }
    }

    data class Challenge(val challengeId: String, val serverId: String, val expiresAt: Long) {
        companion object {
            fun read(data: JsonElement?): Challenge {
                val o = data.objectOrThrow()
                return Challenge(o.str("challenge_id"), o.str("server_id"), o.long("expires_at"))
            }
        }
    }

    data class Verified(val token: String, val expiresAt: Long, val account: MinecraftAccount, val user: PublicUser?) {
        companion object {
            fun read(data: JsonElement?): Verified {
                val o = data.objectOrThrow()
                return Verified(
                    token = o.str("token"),
                    expiresAt = o.long("expires_at"),
                    account = MinecraftAccount.read(o.obj("account")),
                    user = o.objOrNull("user")?.let(PublicUser::read),
                )
            }
        }
    }

    data class Session(val account: MinecraftAccount, val user: PublicUser?, val expiresAt: Long) {
        companion object {
            fun read(data: JsonElement?): Session {
                val o = data.objectOrThrow()
                return Session(MinecraftAccount.read(o.obj("account")), o.objOrNull("user")?.let(PublicUser::read), o.long("expires_at"))
            }
        }
    }

    data class LinkStatus(val linked: Boolean, val user: PublicUser?, val pendingCodeExpiresAt: Long?) {
        companion object {
            fun read(data: JsonElement?): LinkStatus {
                val o = data.objectOrThrow()
                return LinkStatus(o.bool("linked"), o.objOrNull("user")?.let(PublicUser::read), o.longOrNull("pending_code_expires_at"))
            }
        }
    }

    /** A one-time link (`POST /v1/client/link`), valid until [expiresAt]. */
    data class CreatedLink(val code: String, val url: String, val expiresAt: Long) {
        companion object {
            fun read(data: JsonElement?): CreatedLink {
                val o = data.objectOrThrow()
                return CreatedLink(o.str("code"), o.str("url"), o.long("expires_at"))
            }
        }
    }
}

// -- tree reading ---------------------------------------------------------

private fun JsonElement?.objectOrThrow(): JsonObject =
    this as? JsonObject ?: throw JsonParseException("expected an object")

private fun JsonElement?.array(): JsonArray =
    this as? JsonArray ?: throw JsonParseException("expected an array")

private fun <T> JsonArray.items(read: (JsonObject) -> T): List<T> = mapNotNull { element ->
    runCatching { read(element as JsonObject) }.getOrNull()
}

private fun JsonObject.present(key: String): JsonElement? = get(key)?.takeUnless { it.isJsonNull }

private fun JsonObject.required(key: String): JsonElement =
    present(key) ?: throw JsonParseException("missing \"$key\"")

private fun JsonObject.str(key: String): String = required(key).asString

private fun JsonObject.strOrNull(key: String): String? = present(key)?.asString

private fun JsonObject.int(key: String): Int = required(key).asInt

private fun JsonObject.intOrNull(key: String): Int? = present(key)?.asInt

private fun JsonObject.long(key: String): Long = required(key).asLong

private fun JsonObject.longOrNull(key: String): Long? = present(key)?.asLong

private fun JsonObject.bool(key: String): Boolean = required(key).asBoolean

private fun JsonObject.obj(key: String): JsonObject =
    required(key) as? JsonObject ?: throw JsonParseException("\"$key\" is not an object")

private fun JsonObject.objOrNull(key: String): JsonObject? = present(key) as? JsonObject

private fun JsonObject.uuid(key: String): UUID = try {
    UUID.fromString(str(key))
} catch (e: IllegalArgumentException) {
    throw JsonParseException("\"$key\" is not a UUID", e)
}

/** A required list whose malformed items are a hard error (they are part of one object). */
private fun <T> JsonObject.list(key: String, read: (JsonObject) -> T): List<T> {
    val array = present(key) as? JsonArray ?: return emptyList()
    return array.map { read(it as? JsonObject ?: throw JsonParseException("\"$key\" holds a non-object")) }
}
