package de.leicraftmc.leicraftclient.client.assets

import java.security.MessageDigest

/**
 * Checks for the API's content-addressed assets.
 *
 * An asset id is the lowercase hex SHA-256 of the file, so a download can be
 * verified against its own name, and a cached file never goes stale. Ids also
 * become file names in the disk cache, which is why anything that is not
 * exactly 64 hex digits is refused outright (no path tricks).
 */
object AssetIntegrity {

    private const val ID_LENGTH = 64

    fun isValidId(id: String): Boolean =
        id.length == ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** True only when [bytes] really are the asset called [id]. */
    fun matches(id: String, bytes: ByteArray): Boolean =
        isValidId(id) && sha256Hex(bytes).equals(id, ignoreCase = true)

    /** `<id>.<extension>`, normalised to lowercase. */
    fun fileName(id: String, extension: String): String {
        require(isValidId(id)) { "not an asset id: $id" }
        return "${id.lowercase()}.$extension"
    }
}
